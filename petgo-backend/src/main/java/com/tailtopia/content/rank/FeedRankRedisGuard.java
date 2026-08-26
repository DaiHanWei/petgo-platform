package com.tailtopia.content.rank;

import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Redis 不可用时的降级闸（V1.1.6 Story 16.1 · AC5 = 降级链<b>级别 3</b>）。
 *
 * <p>🛡 <b>绝不因缓存不可用而返回空页或报错</b>：曝光记录读不到 ⇒ 当作没看过（系数 1.0）；
 * 序列快照读不到 ⇒ 由调用方实时算当页，接受翻页可能重复。两者都比白屏好得多。
 *
 * <p>🔴 <b>级别 3 与 4 要告警，级别 1、2 不告警</b>（§6.2）：属性池/物种池不足在当前内容体量下
 * 会经常触发，是<b>预期行为</b>，为它告警等于把告警变成噪音、真出事时没人看。
 * 这里统一打 {@value #ALERT_MARKER} 前缀的 WARN，日志侧按这个串配告警规则。
 */
final class FeedRankRedisGuard {

    /** 告警锚点串 —— 日志侧按它配规则，🛡 改动即等于改动告警配置，别随手改。 */
    static final String ALERT_MARKER = "feed-rank-redis-unavailable";

    private static final Logger log = LoggerFactory.getLogger(FeedRankRedisGuard.class);

    private FeedRankRedisGuard() {}

    /**
     * 跑一段 Redis 操作；抛错则记告警并返回兜底值。
     *
     * <p>⚠️ 捕获的是 {@link RuntimeException} 而非具体的连接异常类：Spring Data Redis 会把
     * 连接失败、超时、序列化失败包成好几种不同的 {@code DataAccessException} 子类，
     * 逐个列举必然漏 —— 而这里的兜底对任何一种失败都是同一个正确答案。
     *
     * <p>🛡 日志只记操作名，<b>不记键、不记 id 列表</b>（键含 userId、id 列表是浏览行为）。
     */
    static <T> T guard(String op, Supplier<T> body, T fallback) {
        try {
            return body.get();
        } catch (RuntimeException e) {
            log.warn("{} op={} cls={} msg={}", ALERT_MARKER, op, e.getClass().getSimpleName(),
                    e.getMessage());
            return fallback;
        }
    }

    /** 无返回值版本（写操作）。写失败对读路径无害 —— 下次序列生成少一点曝光信息而已。 */
    static void guardVoid(String op, Runnable body) {
        guard(op, () -> {
            body.run();
            return null;
        }, null);
    }
}
