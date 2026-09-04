package com.tailtopia.share.service;

import com.tailtopia.config.service.PlatformConfigService;
import com.tailtopia.profile.domain.PetProfile;
import com.tailtopia.profile.repository.IdCardRepository;
import com.tailtopia.profile.repository.PetProfileRepository;
import com.tailtopia.share.domain.IdCardShareReward;
import com.tailtopia.share.repository.IdCardShareRewardRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 身份证卡面分享奖励的**渠道层**发放（V1.1.6 Story 18.2）。
 *
 * <h2>🔴 AC3 三层控制，顺序不可换</h2>
 * <ol>
 *   <li><b>档案首次</b> —— 这个宠物档案拿过就不再发（AC4，去重键是档案不是卡）</li>
 *   <li><b>每日上限 M</b> —— 渠道层，WIB 当地日</li>
 *   <li><b>月度全局上限</b> —— {@link ShareRewardService}（18.1），含总开关</li>
 * </ol>
 * 顺序换了会让最便宜的判断排在最贵的后面（档案去重是一次索引命中，
 * 月度额度要动写事务），而且会把额度占掉又退回去。
 *
 * <h2>🛡 AC4：未绑档案的独立建卡不发奖励</h2>
 * 无档案的卡可无限造，是刷量的直接入口。所以拿不到档案就直接不发。
 *
 * <h2>🛡 AC7：幂等靠唯一约束，不靠先查再插</h2>
 * 「先查有没有再插」是典型的并发双发（两个请求都查到"没有"）。
 * 这里先查是为了**省一次写事务**，真正兜底的是 {@code uq_id_card_share_rewards_profile}
 * 撞键 ⇒ 捕获 {@link DataIntegrityViolationException} 后按「已发过」处理。
 *
 * <h2>🛡 AC7：发放失败不影响分享本身</h2>
 * 调用方拿到的是「发了没发」，异常在<b>本类内部消化</b>，绝不外抛。
 * 🔴 <b>不重试、不建补偿队列</b>：分享奖励是锦上添花，
 * 为它加一条重试链路的复杂度远高于「偶尔少发一次」的代价。
 */
@Service
public class IdCardShareRewardService {

    private static final Logger log = LoggerFactory.getLogger(IdCardShareRewardService.class);

    /** WIB。日上限按**当地日**切；UTC 切日会让「今天」在 WIB 早上 7 点才换。 */
    private static final ZoneId WIB = ZoneId.of("Asia/Jakarta");

    /**
     * 钱包幂等键在 Redis 里的前缀，与 {@code IdempotencyService.PREFIX} 逐字一致。
     *
     * <p>⚠️ 那边是 private 常量，这里刻意<b>不改共享层去暴露它</b>（Story 1.2 的
     * pre-commit 写 Redis 模式由 topup/refund/debit 共用，本 story 不动它），
     * 代价是两处字符串要人肉保持一致 —— 改那边前缀时记得同步这里。
     */
    private static final String IDEM_PREFIX = "idem:";

    private final IdCardShareRewardRepository rewards;
    private final PetProfileRepository profiles;
    private final IdCardRepository cards;
    private final PlatformConfigService platformConfig;
    private final ShareRewardService shareReward;
    private final StringRedisTemplate redis;

    /**
     * ⚠️ 用显式 {@link TransactionTemplate} 而不是在 {@code attempt} 上标
     * {@code @Transactional(REQUIRES_NEW)}。
     *
     * <p>🔴 因为 {@code rewardAfterShare} 调 {@code attempt} 是<b>同类自调用</b> ——
     * 不走 Spring 代理，注解上的事务语义**完全不生效**。
     * 那样写的表现是：留痕行立刻提交，抛异常也回滚不掉，于是「没发成却留了痕」，
     * 档案被标成已拿过、用户再也拿不到。本 story 第一版就是这么写的，
     * 靠三条 {@code LeavesNoTrace} 用例红了才发现。
     */
    private final TransactionTemplate tx;

    public IdCardShareRewardService(IdCardShareRewardRepository rewards,
            PetProfileRepository profiles, IdCardRepository cards,
            PlatformConfigService platformConfig, ShareRewardService shareReward,
            PlatformTransactionManager txManager, StringRedisTemplate redis) {
        this.rewards = rewards;
        this.profiles = profiles;
        this.cards = cards;
        this.platformConfig = platformConfig;
        this.shareReward = shareReward;
        this.redis = redis;
        this.tx = new TransactionTemplate(txManager);
        this.tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * 「这个档案已经拿过了」——用异常而不是返回值，因为它必须让 {@link #attempt} 那个
     * 独立事务<b>整体回滚</b>（撞唯一键后事务已 rollback-only，继续做事是错的）。
     */
    static final class AlreadyRewarded extends RuntimeException {
        AlreadyRewarded() {
            super(null, null, false, false); // 正常分支，不需要栈
        }
    }

    /** 「被上限或总开关拦下」——同样要回滚已插入的留痕行：没发成就不该留痕。 */
    static final class NotGranted extends RuntimeException {
        NotGranted() {
            super(null, null, false, false);
        }
    }

    /** 某个时刻属于哪个 WIB 当地日。**唯一实现**（同 {@link ShareRewardService#periodOf}）。 */
    public static LocalDate shareDateOf(Instant at) {
        return at.atZone(WIB).toLocalDate();
    }

    /**
     * 分享成功后试着发奖励。
     *
     * <p>⚠️ 只应在 App 侧拿到系统分享面板 {@code ShareResultStatus.success} 之后调用（AC5）。
     * 用户取消面板 ⇒ App 不调本接口 ⇒ 不发币。这一层<b>不做也无法做</b>那个判断。
     *
     * @return 真的发了多少枚；{@code 0} = 没发（任一层拦下）
     */
    public long rewardAfterShare(long userId, long cardId, Instant at) {
        try {
            return tx.execute(status -> attempt(userId, cardId, at));
        } catch (AlreadyRewarded | NotGranted expected) {
            // 两个**正常分支**：档案已拿过 / 被上限或总开关拦下。都只是"没发"，不是故障。
            return 0;
        } catch (RuntimeException e) {
            // 🛡 AC7：发放失败不影响分享本身。异常在这里终止，绝不外抛给分享链路。
            // 🔴 不重试、不入补偿队列 —— 见类注释。
            log.warn("身份证分享奖励发放失败（已忽略，不影响分享）user={} card={} cls={} msg={}",
                    userId, cardId, e.getClass().getSimpleName(), e.getMessage());
            return 0;
        }
    }

    /**
     * 「值不值得把奖励讲给用户听」—— 返回**可以对外承诺的枚数**，0 = 一个字都不要提。
     *
     * <h2>为什么需要它（产品 2026-08-27）</h2>
     * 分享奖励三个数**默认全是 0**（功能随版本上线、默认一分不发，等运营配好才开始发）。
     * 于是卡面页上凡是提到「分享可得 PawCoin」的文案，都必须**配好了才显示** ——
     * 否则就是 Story 18.2 AC6 反复要避免的那件事：<b>承诺了奖励却不发</b>。
     *
     * <h2>🔴 只看「配没配 + 你还有没有资格」，不看额度</h2>
     * 判据刻意只有三条：总开关、每次发放数、渠道日上限（任一为 0 ⇒ 永远发不出来），
     * 外加「这只宠物是不是已经领过」。
     *
     * <b>刻意不看月度总额度</b>：AC3 明确不许把「额度用完了」讲给用户听 ——
     * 告知会诱导「攒着别分享」或「月初集中刷满」。额度耗尽时这句文案会短暂过承诺，
     * 这是**有意接受的代价**，比诱导刷量便宜得多。
     *
     * <h2>⚠️ 这个奖励是「一只宠物档案只发一次」，不是每次都发</h2>
     * 所以文案必须写「首次分享」。写成「每次分享」在第二次分享时就是假话，
     * 而用户不会去读我们的规则，只会觉得被骗了一次。
     */
    public long advertisableCoins(long userId) {
        try {
            var cfg = platformConfig.pawcoin();
            if (!cfg.isShareRewardEnabled()
                    || cfg.getIdCardShareReward() <= 0
                    || cfg.getIdCardShareDailyCap() <= 0
                    // 🔴 月度上限为 0 或装不下一次发放 → tryGrant 永假：这是配置态而非「额度耗尽」，
                    //    此时继续宣传就是永久性的假承诺（AC6）。
                    || cfg.getShareRewardMonthlyCap() < cfg.getIdCardShareReward()) {
                return 0; // 没配好 —— 一个字都不要提
            }
            Optional<PetProfile> profile = profiles.findByOwnerId(userId);
            if (profile.isEmpty()) {
                return 0; // AC4：无档案本来就不发，别承诺
            }
            if (rewards.findByPetProfileId(profile.get().getId()).isPresent()) {
                return 0; // 这只宠物已经领过（一次性），再提就是假话
            }
            return cfg.getIdCardShareReward();
        } catch (RuntimeException e) {
            // 🛡 与发放同一姿态：这只是一句文案，读配置出问题绝不能把卡面页搞崩。
            log.warn("分享奖励展示判定失败（按不展示处理）user={} cls={}",
                    userId, e.getClass().getSimpleName());
            return 0;
        }
    }

    /**
     * 真正的发放尝试。由 {@link #tx}（REQUIRES_NEW）包起来跑。
     *
     * <p>⚠️ 独立事务而不是复用外层：撞唯一键会让事务进入 rollback-only，
     * 若与分享链路共用事务，「已发过」这个**正常分支**会连带把外层一起弄脏。
     */
    long attempt(long userId, long cardId, Instant at) {
        // ① 卡必须属于本人 —— 否则可以拿别人的卡 id 来刷。
        if (cards.findByIdAndUserId(cardId, userId).isEmpty()) {
            return 0;
        }
        // ② 🛡 AC4：没有宠物档案 ⇒ 不发（无档案的卡可无限造）。
        Optional<PetProfile> profile = profiles.findByOwnerId(userId);
        if (profile.isEmpty()) {
            return 0;
        }
        long profileId = profile.get().getId();

        // ③ 第一层：档案首次。已发过直接返回（省掉后面两层的写事务）。
        if (rewards.findByPetProfileId(profileId).isPresent()) {
            return 0;
        }

        var cfg = platformConfig.pawcoin();
        long coins = cfg.getIdCardShareReward();
        if (coins <= 0) {
            return 0;
        }

        // ④ 第二层：渠道日上限（WIB 当地日）。
        LocalDate day = shareDateOf(at);
        if (cfg.getIdCardShareDailyCap() <= 0
                || rewards.countByUserIdAndShareDate(userId, day) >= cfg.getIdCardShareDailyCap()) {
            return 0;
        }

        // ⑥ 先落留痕行，再占额度。
        //
        // ⚠️ 这个顺序是**成本**取舍，不是正确性取舍 —— 这一点我验证过：
        //    把两步对调后整套测试仍然全绿，因为外层是一个 REQUIRES_NEW 事务，
        //    撞唯一键会让它整体回滚，连同已占的额度一起退回。
        //    （一开始我在这里写的理由是「反过来会导致额度被占两次」，那是错的：
        //     那种情形只在**没有外层事务**时成立，而那正是本 story 第一版自调用的 bug 状态。）
        //
        // 之所以仍选这个顺序：唯一键是最便宜也最确定的闸门，把它放在前面，
        // 并发的第二个请求在碰到钱包与账本之前就被挡住 —— 不必依赖回滚一笔 PawCoin 入账。
        try {
            rewards.saveAndFlush(IdCardShareReward.of(profileId, userId, cardId, coins, day));
        } catch (DataIntegrityViolationException dup) {
            // 并发下别人先发了。⚠️ 撞键已让本事务 rollback-only，必须抛出去让它整体回滚，
            //    不能在同一事务里继续做任何事。
            throw new AlreadyRewarded();
        }

        // ⑦ 第三层：月度全局上限 + 总开关（18.1）。
        //    幂等键用档案 id —— 与去重键同源，重放时 PawCoin 侧也不会重复入账。
        String idemKey = "id-card-share:" + profileId;

        // 🛡 陈旧幂等键清理（review fix）：下面的 credit 会在**本事务提交前**就把幂等键
        //    写进 Redis（Story 1.2 既有共享模式，topup/refund/debit 共用，🔴 不动那一层）。
        //    若本 REQUIRES_NEW 事务在 credit 之后提交失败，DB 全部回滚、Redis 键却留 24h ——
        //    重试时 credit 被这枚陈旧键短路成 no-op，而留痕行与额度照常提交：
        //    档案被标成已拿过、币一枚没到账，正是「没发成就不该留痕」要防的局面。
        //    所以登记同步器：本事务只要**不是成功提交**，就把这枚键删掉。
        //    ⚠️ 误删 / STATUS_UNKNOWN 下删掉一枚其实有效的键都是安全的：钱包幂等有 DB 兜底
        //    （uq_ledger_entries_idem 唯一索引 + isReplay 查总账），Redis 只是快路径，
        //    删掉最多让下一次重放多查一次库，不会重复入账。
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status == STATUS_COMMITTED) {
                    return;
                }
                try {
                    redis.delete(IDEM_PREFIX + idemKey);
                } catch (RuntimeException e) {
                    // Redis 不可用时放弃清理（此时 credit 里的写入多半也没成）。只记 warn，不外抛。
                    log.warn("分享奖励回滚后清理幂等键失败 key={} cls={} msg={}",
                            idemKey, e.getClass().getSimpleName(), e.getMessage());
                }
            }
        });

        if (!shareReward.tryReward(userId, coins, "ID_CARD_SHARE", profileId, idemKey, at)) {
            // 🛡 没发成就不该留痕：抛出去让上面那行 insert 一起回滚。
            //    否则档案会被标成"已拿过"，而用户其实一枚都没拿到——再也拿不到了。
            throw new NotGranted();
        }
        return coins;
    }
}
