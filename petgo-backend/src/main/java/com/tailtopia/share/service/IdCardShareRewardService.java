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
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
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

    private final IdCardShareRewardRepository rewards;
    private final PetProfileRepository profiles;
    private final IdCardRepository cards;
    private final PlatformConfigService platformConfig;
    private final ShareRewardService shareReward;

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
            PlatformTransactionManager txManager) {
        this.rewards = rewards;
        this.profiles = profiles;
        this.cards = cards;
        this.platformConfig = platformConfig;
        this.shareReward = shareReward;
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
        if (!shareReward.tryReward(userId, coins, "ID_CARD_SHARE", profileId,
                "id-card-share:" + profileId, at)) {
            // 🛡 没发成就不该留痕：抛出去让上面那行 insert 一起回滚。
            //    否则档案会被标成"已拿过"，而用户其实一枚都没拿到——再也拿不到了。
            throw new NotGranted();
        }
        return coins;
    }
}
