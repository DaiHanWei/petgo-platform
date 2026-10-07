package com.tailtopia.share.service;

import com.tailtopia.config.domain.PawCoinConfig;
import com.tailtopia.config.service.PlatformConfigService;
import com.tailtopia.profile.domain.PetProfile;
import com.tailtopia.profile.repository.PetProfileRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 「宠物 × 卡类型」去重的分享奖励渠道的**共同流程**（V1.3.2 Story 4.5 · AD-13）。包内私有。
 *
 * <p>对外仍是两个独立 bean、两套常量（{@link TailsonalityShareRewardService} /
 * {@link PassportShareRewardService}）—— 这里只承载逐项照 {@link IdCardShareRewardService} 的骨架，
 * <b>不是渠道注册表</b>（代码核对报告 §1：渠道不是注册表）。
 *
 * <h2>🔴 顺序（AC2.1），不可换</h2>
 * ① 本人唯一宠物（无档案 → 0）→ ② 资格校验 → ③ 宠物 × 卡类型已拿过 → 0 → ④ 渠道单次额（≤0 → 0）
 * → ⑤ 渠道日上限（WIB 日，复用 {@link IdCardShareRewardService#shareDateOf}；先按用户取 advisory 锁再数）
 * → ⑥ {@code saveAndFlush} 留痕（撞唯一键 → 整体回滚）→ ⑦ 登记「非提交则删 Redis 幂等键」同步器
 * → ⑧ {@link ShareRewardService#tryReward}（总开关 + 月度上限），false → 回滚留痕。
 *
 * <h2>🛡 发放失败不影响分享本身</h2>
 * 异常在本类内消化、绝不外抛；不重试、不补偿（与既有两渠道同一姿态）。
 *
 * <h2>⚠️ 显式 {@link TransactionTemplate}（REQUIRES_NEW）</h2>
 * {@code rewardAfterShare → attempt} 是同类自调用，标注解不生效 —— 身份证渠道第一版就是这么写坏的。
 *
 * @param <C> 本渠道的卡类型枚举
 */
abstract class PetCardShareRewardChannel<C extends Enum<C>> {

    private static final Logger log = LoggerFactory.getLogger(PetCardShareRewardChannel.class);

    /** 钱包幂等键在 Redis 里的前缀，与 {@code IdempotencyService.PREFIX} 逐字一致（同既有两渠道，人肉同步）。 */
    private static final String REDIS_IDEM_PREFIX = "idem:";

    @PersistenceContext
    private EntityManager entityManager;

    private final PetProfileRepository profiles;
    private final PlatformConfigService platformConfig;
    private final ShareRewardService shareReward;
    private final StringRedisTemplate redis;
    private final TransactionTemplate tx;

    PetCardShareRewardChannel(PetProfileRepository profiles, PlatformConfigService platformConfig,
            ShareRewardService shareReward, PlatformTransactionManager txManager, StringRedisTemplate redis) {
        this.profiles = profiles;
        this.platformConfig = platformConfig;
        this.shareReward = shareReward;
        this.redis = redis;
        this.tx = new TransactionTemplate(txManager);
        this.tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** 「这只宠物这种卡已经拿过」——异常让独立事务整体回滚。 */
    static final class AlreadyRewarded extends RuntimeException {
        AlreadyRewarded() {
            super(null, null, false, false);
        }
    }

    /** 「被上限或总开关拦下」——回滚已插入的留痕：没发成就不该留痕。 */
    static final class NotGranted extends RuntimeException {
        NotGranted() {
            super(null, null, false, false);
        }
    }

    // ---- 渠道钩子 ----

    /** PawCoin 侧引用类型（{@code TAILSONALITY_SHARE} / {@code PASSPORT_SHARE}）。 */
    abstract String refType();

    /** 钱包幂等键前缀（{@code tailsonality-share:} / {@code passport-share:}）。 */
    abstract String idemPrefix();

    /** 日上限 advisory 锁命名空间（两参形式第一参，全应用唯一）。 */
    abstract int dailyCapLockNamespace();

    abstract long rewardOf(PawCoinConfig cfg);

    abstract int dailyCapOf(PawCoinConfig cfg);

    /** 资格校验（防止没生成过卡就刷接口）。 */
    abstract boolean eligible(long userId, long petProfileId, C cardType);

    abstract boolean alreadyRewarded(long petProfileId, C cardType);

    abstract long countOn(long userId, LocalDate day);

    /** 落留痕行（{@code saveAndFlush}，撞唯一键抛 {@link DataIntegrityViolationException}）。 */
    abstract void record(long petProfileId, long userId, C cardType, long coins, LocalDate day);

    // ---- 共同流程 ----

    /**
     * 分享成功后试着发奖励。⚠️ 只应在 App 拿到系统分享面板成功回调之后调用（取消面板 ⇒ 不调 ⇒ 不发）。
     *
     * @return 真的发了多少枚；{@code 0} = 没发（任一层拦下）
     */
    public long rewardAfterShare(long userId, C cardType, Instant at) {
        try {
            Long coins = tx.execute(status -> attempt(userId, cardType, at));
            return coins == null ? 0 : coins;
        } catch (AlreadyRewarded | NotGranted expected) {
            return 0;
        } catch (RuntimeException e) {
            log.warn("{} 分享奖励发放失败（已忽略，不影响分享）user={} card={} cls={} msg={}",
                    refType(), userId, cardType, e.getClass().getSimpleName(), e.getMessage());
            return 0;
        }
    }

    long attempt(long userId, C cardType, Instant at) {
        // ① 本人唯一宠物（V1 单账号单宠物）。无档案 ⇒ 不发。
        Optional<PetProfile> profile = profiles.findByOwnerId(userId);
        if (profile.isEmpty()) {
            return 0;
        }
        long petId = profile.get().getId();

        // ② 资格：没生成过这种卡的前提数据就不发（防刷接口）。
        if (!eligible(userId, petId, cardType)) {
            return 0;
        }

        // ③ 去重：宠物 × 卡类型拿过就不再发（重测 / 再打卡都不再发）。先查省一次写事务，兜底靠唯一约束。
        if (alreadyRewarded(petId, cardType)) {
            return 0;
        }

        PawCoinConfig cfg = platformConfig.pawcoin();
        long coins = rewardOf(cfg);
        if (coins <= 0) {
            return 0;
        }

        // ⑤ 渠道日上限。🔴 WIB 当地日，复用唯一实现；先按用户加事务级锁再数（「数 → 插」之间串行化）。
        LocalDate day = IdCardShareRewardService.shareDateOf(at);
        entityManager.createNativeQuery("SELECT pg_advisory_xact_lock(:ns, :uid)")
                .setParameter("ns", dailyCapLockNamespace())
                .setParameter("uid", (int) (userId % Integer.MAX_VALUE))
                .getSingleResult();
        int cap = dailyCapOf(cfg);
        if (cap <= 0 || countOn(userId, day) >= cap) {
            return 0;
        }

        // ⑥ 先落留痕，再占额度：唯一键是最便宜也最确定的闸门。
        try {
            record(petId, userId, cardType, coins, day);
        } catch (DataIntegrityViolationException dup) {
            throw new AlreadyRewarded(); // 撞键已让本事务 rollback-only，整体回滚
        }

        // ⑦ 钱包幂等键与去重键同源：重放不重复入账。非成功提交 ⇒ 删 Redis 里提前写下的键。
        String idemKey = idemPrefix() + petId + ":" + cardType.name();
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status == STATUS_COMMITTED) {
                    return;
                }
                try {
                    redis.delete(REDIS_IDEM_PREFIX + idemKey);
                } catch (RuntimeException e) {
                    log.warn("{} 分享奖励回滚后清理幂等键失败 key={} cls={}",
                            refType(), idemKey, e.getClass().getSimpleName());
                }
            }
        });

        // ⑧ 全局层：总开关 + 月度上限。
        if (!shareReward.tryReward(userId, coins, refType(), null, idemKey, at)) {
            throw new NotGranted();
        }
        return coins;
    }
}
