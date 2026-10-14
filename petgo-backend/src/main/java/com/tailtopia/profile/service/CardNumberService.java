package com.tailtopia.profile.service;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.LocalDate;
import java.time.ZoneId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 宠物身份码 / 护照号生成（spec-ktp-pet-idcode-numbering，规则来源《宠物身份码护照编码规则》）。
 *
 * <p>编码规则：
 * <ul>
 *   <li><b>身份码</b> {@code TT+DDMMYY+SP+XXXX}（14 位）：DD=生日「日」+性别加码（母 +50 / 公 +10 /
 *       未知 +0），MM/YY 取生日月与年后两位；SP 物种码 狗 01 / 猫 02 / 其他或未选 00；
 *       XXXX=同一 <b>WIB（Asia/Jakarta）登记日</b> + 同物种顺序号，从 0001 起。</li>
 *   <li><b>护照号</b> {@code TT+SP+P+YY+XXXXX}（12 位）：YY=WIB 签发年后两位，XXXXX=当年顺序号从 00001 起。
 *       按构造年内唯一（计数器单调），无需撞号循环。</li>
 * </ul>
 *
 * <p>并发正确性：计数器表 {@code id_card_no_counters} / {@code passport_no_counters} 用
 * {@code INSERT .. ON CONFLICT .. DO UPDATE .. RETURNING} 单语句原子取号，同 (登记日,物种)/(年)
 * 的并发请求被计数器<b>行锁天然串行</b>，序号不重不漏——纯 DB 内解决，不引入 MQ/缓存中间件（架构护栏 F5），
 * 与 legacy 号池 {@link SerialAllocationService} 的 advisory 锁互不相干、互不影响。
 *
 * <p>撞号兜底：身份码日期段=生日、顺序号却按登记日计，不同登记日的同生日同性别同物种宠物可能拼出同号——
 * 拼出的号若已登记在归属表 {@code id_card_number_owners} 则继续取下一个序号，直到序号耗尽（9999）。
 * V1.3.4 ktp Story 2.2（AD-3）：全局 UNIQUE（V95）已删除（同一用户的多张卡可共用一个号），
 * <b>归属表主键 + {@link #claimNumbers}</b> 为终极兜底——跨用户撞号整笔建卡回滚。
 * <b>不设固定次数上限</b>：同前缀历史卡跨日累积会永久占用低位序号，固定上限会让该组合
 * 从某天起建卡恒失败；以 9999 为界则至多退化为一次线性扫描（≤500 DAU 不可达）。
 * 序号超上限（身份码 9999 / 护照号 99999）→ 500（按 spec「Ask First」之外的默认策略直接报错）。
 *
 * <p>两个方法须在<b>调用方事务内</b>执行（默认 REQUIRED 传播，join {@code IdCardService.createCard}），
 * 行锁随事务提交/回滚释放，保证「取号 → 落卡」原子；失败回滚时序号出现空洞可接受（顺序号仅展示语义）。
 */
@Service
public class CardNumberService {

    private static final ZoneId WIB = ZoneId.of("Asia/Jakarta");
    private static final Logger log = LoggerFactory.getLogger(CardNumberService.class);

    /** 建卡按用户串行的 advisory 锁命名空间（V1.3.4 ktp Story 2.3）："ICCR"，与 AGEC / TSSR / PPSR 不撞。 */
    static final int CARD_CREATE_LOCK_NS = 0x4943_4352;

    @PersistenceContext
    private EntityManager entityManager;

    /**
     * 生成并占用一个身份码 {@code TT+DDMMYY+SP+XXXX}。gender 传已归一化的
     * {@code MALE/FEMALE/UNKNOWN}（null 按未知），petType 传枚举名快照（DOG/CAT/... 或 null）。
     */
    @Transactional
    public String allocateCardNo(LocalDate birthday, String gender, String petType) {
        String sp = speciesCode(petType);
        LocalDate regDate = LocalDate.now(WIB);
        while (true) {
            int seq = nextCardSeq(regDate, sp);
            if (seq > 9999) {
                throw new IllegalStateException("身份码顺序号超出当日上限（9999）");
            }
            String candidate = composeCardNo(birthday, gender, sp, seq);
            if (!cardNoExists(candidate)) {
                return candidate;
            }
            // 撞上已登记的号（归属表兜底面）→ 继续取下一个序号，序号耗尽由上方 9999 界终止。
        }
    }

    /** 生成并占用一个护照号 {@code TT+SP+P+YY+XXXXX}。按构造年内唯一，无需撞号循环。 */
    @Transactional
    public String allocatePassportNo(String petType) {
        int year = LocalDate.now(WIB).getYear();
        Number next = (Number) entityManager.createNativeQuery(
                        "INSERT INTO passport_no_counters (issue_year, next_seq) VALUES (:y, 1) "
                                + "ON CONFLICT (issue_year) DO UPDATE "
                                + "SET next_seq = passport_no_counters.next_seq + 1 "
                                + "RETURNING next_seq")
                .setParameter("y", year)
                .getSingleResult();
        int seq = next.intValue();
        if (seq > 99999) {
            throw new IllegalStateException("护照号顺序号超出当年上限（99999）");
        }
        return composePassportNo(petType, year, seq);
    }

    /**
     * 对该用户取事务级 advisory 锁（V1.3.4 ktp Story 2.3，AD-5）：同一用户并发两次建卡对「沿用 / 新发」的判定一致。
     * 两参形式按用户分锁，随调用方事务提交 / 回滚释放；须在 {@code IdCardService.createCard} 事务内、查沿用来源前调用。
     * 与号池单参锁（{@link SerialAllocationService}）是两把锁，取锁顺序固定（先用户锁、后号池锁），不会死锁。
     */
    @Transactional(propagation = Propagation.MANDATORY) // 事务外调用即失败：xact 锁离开事务就等于没锁
    public void lockOwnerForCardCreation(long userId) {
        entityManager.createNativeQuery("SELECT pg_advisory_xact_lock(:ns, :uid)")
                .setParameter("ns", CARD_CREATE_LOCK_NS)
                .setParameter("uid", (int) (userId % Integer.MAX_VALUE))
                .getSingleResult();
    }

    /**
     * 登记本卡两个号的归属（V1.3.4 ktp Story 2.2，AD-3）。须在建卡事务内、落卡前调用（REQUIRED 加入
     * {@code IdCardService.createCard}）。对每个号：{@code INSERT .. ON CONFLICT DO NOTHING} 后查归属——
     * 本人（刚登记或早已登记）→ 通过；<b>他人 → 抛 {@link IllegalStateException}，整笔建卡回滚</b>（发号出错的兜底，
     * T-17：500 级系统错误）。并发下 PG 的 ON CONFLICT 会等待对方未提交的同键插入，对方提交后本方读到对方归属。
     * null 的号跳过（防御；新卡两个号恒非空）。日志不记号码（编码了宠物生日）与对方 user_id。
     */
    @Transactional
    public void claimNumbers(long userId, String cardNo, String passportNo) {
        claim(userId, "C", cardNo);
        claim(userId, "P", passportNo);
    }

    private void claim(long userId, String kind, String number) {
        if (number == null) {
            return;
        }
        entityManager.createNativeQuery(
                        "INSERT INTO id_card_number_owners (kind, number, user_id) VALUES (:kind, :no, :uid) "
                                + "ON CONFLICT (kind, number) DO NOTHING")
                .setParameter("kind", kind)
                .setParameter("no", number)
                .setParameter("uid", userId)
                .executeUpdate();
        Number owner = (Number) entityManager.createNativeQuery(
                        "SELECT user_id FROM id_card_number_owners WHERE kind = :kind AND number = :no")
                .setParameter("kind", kind)
                .setParameter("no", number)
                .getSingleResult();
        if (owner.longValue() != userId) {
            log.error("身份证编号跨用户冲突，建卡回滚 userId={} kind={}", userId, kind);
            throw new IllegalStateException("身份证编号已属于其他用户");
        }
    }

    /** 身份码拼装（纯函数，包内可见供单测）：TT + (日+性别加码)(2) + 月(2) + 年后两位(2) + SP(2) + 序号(4)。 */
    static String composeCardNo(LocalDate birthday, String gender, String speciesCode, int seq) {
        int day = birthday.getDayOfMonth() + genderOffset(gender);
        return String.format("TT%02d%02d%02d%s%04d",
                day, birthday.getMonthValue(), birthday.getYear() % 100, speciesCode, seq);
    }

    /** 护照号拼装（纯函数，包内可见供单测）：TT + SP(2) + P + 年后两位(2) + 序号(5)。 */
    static String composePassportNo(String petType, int year, int seq) {
        return String.format("TT%sP%02d%05d", speciesCode(petType), year % 100, seq);
    }

    /** 性别加码：母 +50 / 公 +10 / 未知或未传 +0。 */
    static int genderOffset(String gender) {
        if ("FEMALE".equals(gender)) {
            return 50;
        }
        if ("MALE".equals(gender)) {
            return 10;
        }
        return 0;
    }

    /**
     * 物种 SP 码的公开转发（V1.3.2 Story 1.2）：宠物护照号源按物种段比对 KTP 卡的护照号。
     * 规则本身仍在 {@link #speciesCode}，不复制。
     */
    public static String speciesCodeOf(String petType) {
        return speciesCode(petType);
    }

    /** 物种 SP 码：狗 01 / 猫 02 / 其他或未选 00。 */
    static String speciesCode(String petType) {
        if ("DOG".equals(petType)) {
            return "01";
        }
        if ("CAT".equals(petType)) {
            return "02";
        }
        return "00";
    }

    /** (WIB 登记日, 物种) 计数器单语句原子取号（行锁串行，参照 {@link SerialAllocationService} 的原生 SQL 写法）。 */
    private int nextCardSeq(LocalDate regDate, String speciesCode) {
        Number next = (Number) entityManager.createNativeQuery(
                        "INSERT INTO id_card_no_counters (reg_date, species, next_seq) VALUES (:d, :sp, 1) "
                                + "ON CONFLICT (reg_date, species) DO UPDATE "
                                + "SET next_seq = id_card_no_counters.next_seq + 1 "
                                + "RETURNING next_seq")
                .setParameter("d", regDate)
                .setParameter("sp", speciesCode)
                .getSingleResult();
        return next.intValue();
    }

    /** 号是否已被任何人登记（AD-3：查归属表，不再查 id_cards；新发的号必须全新）。 */
    private boolean cardNoExists(String cardNo) {
        Boolean exists = (Boolean) entityManager.createNativeQuery(
                        "SELECT EXISTS(SELECT 1 FROM id_card_number_owners WHERE kind = 'C' AND number = :no)")
                .setParameter("no", cardNo)
                .getSingleResult();
        return Boolean.TRUE.equals(exists);
    }
}
