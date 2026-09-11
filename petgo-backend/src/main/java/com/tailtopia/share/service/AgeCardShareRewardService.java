package com.tailtopia.share.service;

import com.tailtopia.config.service.PlatformConfigService;
import com.tailtopia.share.domain.AgeCardShareReward;
import com.tailtopia.share.repository.AgeCardShareRewardRepository;
import java.time.Instant;
import java.time.LocalDate;
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
 * 宠物年龄卡分享奖励的**渠道层**发放（V1.3.0 批次 A · Story 5.3 · AD-A20）。
 *
 * <h2>🔴 AC4 三层控制，顺序不可换</h2>
 * <ol>
 *   <li><b>去重命中</b> —— 这次分享上报过就不再发（去重键是<b>幂等键</b>，不是档案）</li>
 *   <li><b>渠道日上限</b> —— WIB 当地日</li>
 *   <li><b>月度全局上限</b> —— {@link ShareRewardService}，含总开关</li>
 * </ol>
 * 顺序换了会让最便宜的判断排在最贵的后面（去重是一次索引命中，月度额度要动写事务），
 * 而且会把额度占掉又退回去。
 *
 * <h2>🔴 与身份证渠道的唯一实质差别：不做档案级去重（决策 A-8）</h2>
 * 身份证按 {@code pet_profile_id} 唯一 —— 一个档案一辈子只发一次。
 * 年龄卡**没有卡实体、也不该按档案唯一**：同一只宠物隔几个月再生成是不同的分享物。
 * 于是本渠道的频次闸门**只剩日上限**（身份证那边日上限是冗余保险，这里它是唯一的那道）。
 *
 * <h2>🔴 日界按 WIB，且复用唯一实现</h2>
 * 直接调 {@link IdCardShareRewardService#shareDateOf}，**不另写一份时区换算**。
 * 按 UTC 切会让「今天」在印尼早上 7 点才换，运营配「3 次/日」时用户在 06:00–07:00
 * 能领双份 —— 这是**资金口径错误**，不是体验问题。
 *
 * <h2>🛡 发放失败不影响分享本身</h2>
 * 调用方拿到的是「发了没发」，异常在本类内部消化，绝不外抛。
 * 🔴 <b>不重试、不建补偿队列</b>：分享奖励是锦上添花，为它加一条重试链路的复杂度
 * 远高于「偶尔少发一次」的代价（与身份证渠道同一姿态）。
 */
@Service
public class AgeCardShareRewardService {

    private static final Logger log = LoggerFactory.getLogger(AgeCardShareRewardService.class);

    /** PawCoin 侧的引用类型，与身份证渠道的 {@code ID_CARD_SHARE} 并列。 */
    static final String REF_TYPE = "AGE_CARD_SHARE";

    /** 渠道幂等键前缀：让钱包侧一眼看得出是哪个渠道，也避免与其它渠道的键撞上。 */
    static final String CHANNEL_PREFIX = "age-card-share:";

    /**
     * 钱包幂等键在 Redis 里的前缀，与 {@code IdempotencyService.PREFIX} 逐字一致。
     *
     * <p>⚠️ 那边是 private 常量，这里与身份证渠道一样**不改共享层去暴露它** ——
     * 代价是三处字符串要人肉保持一致，改那边前缀时记得同步。
     */
    private static final String REDIS_IDEM_PREFIX = "idem:";

    private final AgeCardShareRewardRepository rewards;
    private final PlatformConfigService platformConfig;
    private final ShareRewardService shareReward;
    private final StringRedisTemplate redis;

    /**
     * ⚠️ 显式 {@link TransactionTemplate} 而不是在 {@code attempt} 上标
     * {@code @Transactional(REQUIRES_NEW)} —— {@code rewardAfterShare} 调 {@code attempt}
     * 是**同类自调用**，不走 Spring 代理，注解上的事务语义完全不生效。
     * 身份证渠道第一版就是这么写坏的：留痕行立刻提交、抛异常也回滚不掉，
     * 于是「没发成却留了痕」。这里照抄它修好之后的形状。
     */
    private final TransactionTemplate tx;

    public AgeCardShareRewardService(AgeCardShareRewardRepository rewards,
            PlatformConfigService platformConfig, ShareRewardService shareReward,
            PlatformTransactionManager txManager, StringRedisTemplate redis) {
        this.rewards = rewards;
        this.platformConfig = platformConfig;
        this.shareReward = shareReward;
        this.redis = redis;
        this.tx = new TransactionTemplate(txManager);
        this.tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** 「这次分享已经发过了」——用异常，因为它必须让独立事务整体回滚。 */
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

    /**
     * 分享成功后试着发奖励。
     *
     * <p>⚠️ 只应在 App 侧拿到系统分享面板成功回调之后调用。用户取消面板 ⇒ App 不调本接口
     * ⇒ 不发币。这一层<b>不做也无法做</b>那个判断。
     *
     * <p>🛡 AC6：入参只有**用户、幂等键、时刻** —— 没有卡面内容、没有图片。
     * 「年龄卡不落服务端」指的是卡片图像；领奖是一次独立的服务端调用，是已澄清的唯一例外。
     *
     * @return 真的发了多少枚；{@code 0} = 没发（任一层拦下）
     */
    public long rewardAfterShare(long userId, String clientIdempotencyKey, Instant at) {
        try {
            return tx.execute(status -> attempt(userId, clientIdempotencyKey, at));
        } catch (AlreadyRewarded | NotGranted expected) {
            // 两个**正常分支**：这次分享已发过 / 被上限或总开关拦下。都只是"没发"，不是故障。
            return 0;
        } catch (RuntimeException e) {
            // 🛡 发放失败不影响分享本身。异常在这里终止，绝不外抛给分享链路。
            log.warn("年龄卡分享奖励发放失败（已忽略，不影响分享）user={} cls={} msg={}",
                    userId, e.getClass().getSimpleName(), e.getMessage());
            return 0;
        }
    }

    long attempt(long userId, String clientIdempotencyKey, Instant at) {
        // 幂等键**带上 userId**：客户端给的那串只在它自己那台设备上唯一，
        // 直接当全局唯一键用，等于让两个用户有概率互相顶掉对方的发放。
        String idemKey = CHANNEL_PREFIX + userId + ":" + clientIdempotencyKey;

        // ① 第一层：去重命中。已发过直接返回（省掉后面两层的写事务）。
        if (rewards.findByIdempotencyKey(idemKey).isPresent()) {
            return 0;
        }

        var cfg = platformConfig.pawcoin();
        long coins = cfg.getAgeCardShareReward();
        if (coins <= 0) {
            return 0;
        }

        // ② 第二层：渠道日上限。
        // 🔴 WIB 当地日，复用唯一实现，不另写换算 —— 按 UTC 切会让用户在 06:00–07:00 领双份。
        LocalDate day = IdCardShareRewardService.shareDateOf(at);
        if (cfg.getAgeCardShareDailyCap() <= 0
                || rewards.countByUserIdAndShareDate(userId, day) >= cfg.getAgeCardShareDailyCap()) {
            return 0;
        }

        // ③ 先落留痕行，再占额度：唯一键是最便宜也最确定的闸门，放在前面，
        //    并发的第二个请求在碰到钱包与账本之前就被挡住。
        try {
            rewards.saveAndFlush(AgeCardShareReward.of(userId, coins, day, idemKey));
        } catch (DataIntegrityViolationException dup) {
            // 并发下别人先发了。⚠️ 撞键已让本事务 rollback-only，必须抛出去整体回滚。
            throw new AlreadyRewarded();
        }

        // 🛡 陈旧幂等键清理（与身份证渠道同一处理，同一个已知陷阱）：
        //    下面的 tryReward 会在**本事务提交前**就把幂等键写进 Redis（共享层既有模式）。
        //    若本 REQUIRES_NEW 事务在那之后提交失败，DB 全部回滚、Redis 键却留 24h ——
        //    重试时入账被这枚陈旧键短路成 no-op，而留痕行与额度照常提交：
        //    「留了痕、币一枚没到账」，正是「没发成就不该留痕」要防的局面。
        //    所以登记同步器：本事务只要不是成功提交，就把这枚键删掉。
        //    ⚠️ 误删是安全的：钱包幂等有 DB 兜底（唯一索引 + 查总账），Redis 只是快路径。
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status == STATUS_COMMITTED) {
                    return;
                }
                try {
                    redis.delete(REDIS_IDEM_PREFIX + idemKey);
                } catch (RuntimeException e) {
                    log.warn("年龄卡分享奖励回滚后清理幂等键失败 key={} cls={}",
                            idemKey, e.getClass().getSimpleName());
                }
            }
        });

        // ④ 第三层：月度全局上限 + 总开关。**年龄卡不设独立月度上限**（AC5），共用全局那一个。
        //    幂等键与去重键同源：重放时 PawCoin 侧也不会重复入账。
        if (!shareReward.tryReward(userId, coins, REF_TYPE, null, idemKey, at)) {
            // 🛡 没发成就不该留痕：抛出去让上面那行 insert 一起回滚。
            throw new NotGranted();
        }
        return coins;
    }
}
