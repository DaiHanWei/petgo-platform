package com.tailtopia.profile.service;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.LocalDate;
import java.time.ZoneId;
import org.springframework.stereotype.Service;
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
 * 拼出的号若已存在于 {@code id_cards} 则继续取下一个序号，直到序号耗尽（9999），DB UNIQUE 约束（V95）
 * 为终极兜底。<b>不设固定次数上限</b>：同前缀历史卡跨日累积会永久占用低位序号，固定上限会让该组合
 * 从某天起建卡恒失败；以 9999 为界则至多退化为一次线性扫描（≤500 DAU 不可达）。
 * 序号超上限（身份码 9999 / 护照号 99999）→ 500（按 spec「Ask First」之外的默认策略直接报错）。
 *
 * <p>两个方法须在<b>调用方事务内</b>执行（默认 REQUIRED 传播，join {@code IdCardService.createCard}），
 * 行锁随事务提交/回滚释放，保证「取号 → 落卡」原子；失败回滚时序号出现空洞可接受（顺序号仅展示语义）。
 */
@Service
public class CardNumberService {

    private static final ZoneId WIB = ZoneId.of("Asia/Jakarta");

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
            // 撞上历史登记日发出的同号（UNIQUE 兜底面）→ 继续取下一个序号，序号耗尽由上方 9999 界终止。
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

    private boolean cardNoExists(String cardNo) {
        Boolean exists = (Boolean) entityManager.createNativeQuery(
                        "SELECT EXISTS(SELECT 1 FROM id_cards WHERE card_no = :no)")
                .setParameter("no", cardNo)
                .getSingleResult();
        return Boolean.TRUE.equals(exists);
    }
}
