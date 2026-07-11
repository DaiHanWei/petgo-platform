package com.tailtopia.pay.service;

import com.tailtopia.pay.domain.PayChannel;
import com.tailtopia.pay.domain.PaymentIntent;
import com.tailtopia.pay.domain.PaymentPurpose;
import com.tailtopia.pay.dto.PaymentIntentResponse;
import com.tailtopia.pay.event.PaymentIntentPaidEvent;
import com.tailtopia.pay.repository.PaymentIntentRepository;
import com.tailtopia.profile.service.CardTokenGenerator;
import com.tailtopia.shared.error.AppException;
import com.tailtopia.shared.pay.GatewayStatus;
import com.tailtopia.shared.pay.PaymentCallback;
import com.tailtopia.shared.ratelimit.IdempotencyService;
import com.tailtopia.shared.ratelimit.RedisRateLimiter;
import java.time.Duration;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 支付意图状态机 service（Story 1.1）。两职责：
 * <ol>
 *   <li><b>幂等建单</b>：{@link #createIntent}——复用 {@link IdempotencyService}，同 {@code Idempotency-Key}
 *       重放取回既有意图、不重复建单；写端点 {@link RedisRateLimiter} 限流。</li>
 *   <li><b>回调/轮询单一收口</b>：{@link #applyCallback}——两通道同一入口，Redis 前置 + {@code gateway_ref}
 *       唯一约束 + 「已终态即幂等返回」三闸，双通道<b>只推进一次</b>；到账发 {@link PaymentIntentPaidEvent}
 *       （本 story 不含消费者，入账由 1.2/1.3 挂 hook）。</li>
 * </ol>
 *
 * <p>本 story 意图<b>不直接改余额</b>；{@link #applyCallback} 与到账事件在<b>同一 {@code @Transactional}</b>
 * 内，供下游以 {@code BEFORE_COMMIT} 同事务入账（禁 AFTER_COMMIT 异步，见 1.3 Dev Notes）。
 */
@Service
public class PaymentIntentService {

    private static final Logger log = LoggerFactory.getLogger(PaymentIntentService.class);

    /** 回调去重 Redis 前置 TTL（回调可能超 24h，DB 唯一约束为权威兜底）。 */
    private static final Duration DEDUP_TTL = Duration.ofHours(24);
    private static final String DEDUP_PREFIX = "pay:cb:";

    private final PaymentIntentRepository intents;
    private final IdempotencyService idempotency;
    private final CardTokenGenerator tokenGenerator;
    private final RedisRateLimiter rateLimiter;
    private final StringRedisTemplate redis;
    private final ApplicationEventPublisher events;

    public PaymentIntentService(PaymentIntentRepository intents, IdempotencyService idempotency,
            CardTokenGenerator tokenGenerator, RedisRateLimiter rateLimiter,
            StringRedisTemplate redis, ApplicationEventPublisher events) {
        this.intents = intents;
        this.idempotency = idempotency;
        this.tokenGenerator = tokenGenerator;
        this.rateLimiter = rateLimiter;
        this.redis = redis;
        this.events = events;
    }

    /**
     * 幂等建意图（PENDING）。同 {@code idempotencyKey} 重放取回既有意图。收款/扣费在下游 story 接。
     */
    @Transactional
    public PaymentIntentResponse createIntent(long userId, PaymentPurpose purpose, PayChannel channel,
            long amount, String currency, String idempotencyKey) {
        rateLimiter.check("rl:pay:create:" + userId, 20, Duration.ofMinutes(1));

        Optional<Long> existing = idempotency.findResourceId(idempotencyKey);
        if (existing.isPresent()) {
            return intents.findById(existing.get())
                    .map(PaymentIntentResponse::of)
                    .orElseThrow(() -> AppException.notFound("支付意图不存在"));
        }

        PaymentIntent intent = PaymentIntent.create(
                userId, purpose, channel, amount, currency, tokenGenerator.generate());
        PaymentIntent saved = intents.save(intent);
        idempotency.store(idempotencyKey, saved.getId());
        return PaymentIntentResponse.of(saved);
    }

    /**
     * 回调/轮询单一收口，幂等推进意图。双通道（回调 + 轮询）各到一次也只推进一次：
     * Redis 前置短路 + 「已终态即返回」+ {@code gateway_ref} 唯一约束库级兜底。
     *
     * <p>{@link GatewayStatus#PENDING} 不推进；无匹配意图仅记日志忽略（不因未知回调改任何状态）。
     */
    @Transactional
    public void applyCallback(PaymentCallback cb) {
        if (cb == null || cb.status() == GatewayStatus.PENDING) {
            return; // 未终态不推进
        }
        PaymentIntent intent = resolve(cb);
        if (intent == null) {
            log.warn("支付回调无匹配意图，忽略"); // 不打印 order_id/ref 外的正文/凭证
            return;
        }

        // Redis 前置：同一去重键已标记 + DB 已终态 → 双通道重放，短路（DB 仍为权威）。
        String dedupKey = DEDUP_PREFIX
                + (intent.getGatewayRef() != null ? intent.getGatewayRef() : intent.getPublicToken());
        Boolean fresh = redis.opsForValue().setIfAbsent(dedupKey, "1", DEDUP_TTL);
        if (Boolean.FALSE.equals(fresh) && intent.getStatus().isTerminal()) {
            return;
        }
        // 幂等权威：已终态不重复推进（回调 + 轮询二次到达在此拦下）。
        if (intent.getStatus().isTerminal()) {
            return;
        }

        // 首次回填网关订单号（gateway_ref 唯一 → 跨意图撞号由库约束兜底）。
        if (intent.getGatewayRef() == null && cb.gatewayRef() != null) {
            intent.attachGatewayRef(cb.gatewayRef(), null);
        }

        switch (cb.status()) {
            case PAID -> {
                intent.markPaid(cb.rawMeta());
                intents.saveAndFlush(intent); // @Version 乐观锁裁决并发
                events.publishEvent(new PaymentIntentPaidEvent(
                        intent.getId(), intent.getPublicToken(), intent.getUserId(),
                        intent.getPurpose(), intent.getChannel(), intent.getAmount(), intent.getCurrency()));
            }
            case FAILED -> {
                intent.markFailed(cb.rawMeta());
                intents.saveAndFlush(intent);
            }
            case EXPIRED -> {
                intent.markExpired(cb.rawMeta());
                intents.saveAndFlush(intent);
            }
            default -> { /* PENDING 已在入口挡下，不可达 */ }
        }
    }

    /** 按对外 token 读意图（供同模块下单/查询用；跨模块请走 DTO）。 */
    @Transactional(readOnly = true)
    public Optional<PaymentIntent> findByToken(String publicToken) {
        return intents.findByPublicToken(publicToken);
    }

    /**
     * 收款创建成功后回填网关订单号 + 脱敏快照（Story 1.3 下单时调）。幂等：已回填则直接返回，
     * 避免同 {@code Idempotency-Key} 重放二次下单重复 charge。
     */
    @Transactional
    public void attachCharge(String publicToken, String gatewayRef, java.util.Map<String, Object> meta) {
        PaymentIntent intent = intents.findByPublicToken(publicToken)
                .orElseThrow(() -> AppException.notFound("支付意图不存在"));
        if (intent.getGatewayRef() != null) {
            return; // 已下单，幂等短路
        }
        intent.attachGatewayRef(gatewayRef, meta);
        intents.saveAndFlush(intent);
    }

    /** 先按 {@code gateway_ref}（唯一去重键）定位，回退 {@code public_token}（order_id）。 */
    private PaymentIntent resolve(PaymentCallback cb) {
        if (cb.gatewayRef() != null) {
            Optional<PaymentIntent> byRef = intents.findByGatewayRef(cb.gatewayRef());
            if (byRef.isPresent()) {
                return byRef.get();
            }
        }
        if (cb.orderId() != null) {
            return intents.findByPublicToken(cb.orderId()).orElse(null);
        }
        return null;
    }
}
