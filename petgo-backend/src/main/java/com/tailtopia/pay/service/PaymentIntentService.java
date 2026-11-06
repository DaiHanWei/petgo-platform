package com.tailtopia.pay.service;

import com.tailtopia.pay.domain.PayChannel;
import com.tailtopia.pay.domain.PaymentFailureCategory;
import com.tailtopia.pay.domain.PaymentIntent;
import com.tailtopia.pay.domain.PaymentPurpose;
import com.tailtopia.pay.domain.PaymentStatus;
import com.tailtopia.pay.dto.PaymentIntentResponse;
import com.tailtopia.pay.event.PaymentIntentFailedEvent;
import com.tailtopia.pay.event.PaymentIntentPaidEvent;
import com.tailtopia.pay.repository.PaymentIntentRepository;
import com.tailtopia.profile.service.CardTokenGenerator;
import com.tailtopia.shared.error.AppException;
import com.tailtopia.shared.pay.GatewayStatus;
import com.tailtopia.shared.pay.PaymentCallback;
import com.tailtopia.shared.ratelimit.IdempotencyService;
import com.tailtopia.shared.ratelimit.RedisRateLimiter;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
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
 *   <li><b>回调/轮询单一收口</b>：{@link #applyResult}（原 {@code applyCallback}，保留委托）——两通道同一入口，Redis 前置 + {@code gateway_ref}
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
     * 幂等建意图（PENDING，无时间过期）。同 {@code idempotencyKey} 重放取回既有意图。
     */
    @Transactional
    public PaymentIntentResponse createIntent(long userId, PaymentPurpose purpose, PayChannel channel,
            long amount, String currency, String idempotencyKey) {
        return createIntent(userId, purpose, channel, amount, currency, idempotencyKey, null);
    }

    /**
     * 幂等建意图（PENDING）。同 {@code idempotencyKey} 重放取回既有意图。
     * {@code ttl} 非空即设付款窗过期（PAWCOIN_TOPUP 传 60min，V85）；null = 无时间过期。
     */
    @Transactional
    public PaymentIntentResponse createIntent(long userId, PaymentPurpose purpose, PayChannel channel,
            long amount, String currency, String idempotencyKey, Duration ttl) {
        rateLimiter.check("rl:pay:create:" + userId, 20, Duration.ofMinutes(1));

        // 映射可能指向一行不存在的意图：建意图的事务在映射写入 Redis 之后回滚了（Redis 不随库回滚）。
        // 此时当作没有既有意图、下方新建并覆盖映射；以前这里直接 404，用户重试会一直卡住。
        Optional<PaymentIntent> existing = idempotency.findResourceId(idempotencyKey)
                .flatMap(intents::findById);
        if (existing.isPresent()) {
            PaymentIntent ex = existing.get();
            // 仅【PAID】(已付幂等) 或【PENDING 且未过窗】(复用同一 QR) 才返回既有意图；
            // 其余（已 EXPIRED / PENDING 已过窗 / 失败）→ 落到下方新建，出新码（bug：超付款窗后重开应出新码非旧码）。
            // 末尾 idempotency.store 覆盖映射到新意图（Redis SET 覆盖，非 NX）。
            boolean reusable = ex.getUserId() != null && ex.getUserId() == userId
                    && (ex.getStatus() == PaymentStatus.PAID
                    || (ex.getStatus() == PaymentStatus.PENDING && !ex.isExpiredAt(Instant.now())));
            if (reusable) {
                return PaymentIntentResponse.of(ex);
            }
            if (ex.getStatus() == PaymentStatus.PENDING) {
                ex.markExpired(null); // 仍 PENDING 但已过窗 → 懒过期（已 EXPIRED 则无需再置）
                intents.saveAndFlush(ex);
            }
        }

        Instant expiresAt = ttl == null ? null : Instant.now().plus(ttl);
        PaymentIntent intent = PaymentIntent.create(
                userId, purpose, channel, amount, currency, tokenGenerator.generate(), expiresAt);
        PaymentIntent saved = intents.save(intent);
        idempotency.store(idempotencyKey, saved.getId());
        return PaymentIntentResponse.of(saved);
    }

    /**
     * 幂等建<b>混合支付</b>意图（Story 3.8，电商订单专用）。
     *
     * <p>🔴 {@code amount} 是订单总额，{@code coinAmount + cashAmount} 必须等于它 ——
     * 库级 {@code ck_payment_intents_mixed_shape} 强制这条不变式（3.3 建立）。
     * {@code coinRatio} 由此就地算出，<b>只作展示与审计冗余，绝不参与计算</b>（AD-2）。
     */
    @Transactional
    public PaymentIntentResponse createMixedIntent(long userId, PaymentPurpose purpose,
            long amount, long coinAmount, long cashAmount, String currency,
            String idempotencyKey, Duration ttl) {
        rateLimiter.check("rl:pay:create:" + userId, 20, Duration.ofMinutes(1));

        // 映射可能指向一行不存在的意图：建意图的事务在映射写入 Redis 之后回滚了（Redis 不随库回滚）。
        // 此时当作没有既有意图、下方新建并覆盖映射；以前这里直接 404，用户重试会一直卡住。
        Optional<PaymentIntent> existing = idempotency.findResourceId(idempotencyKey)
                .flatMap(intents::findById);
        if (existing.isPresent()) {
            PaymentIntent ex = existing.get();
            boolean reusable = ex.getUserId() != null && ex.getUserId() == userId
                    && (ex.getStatus() == PaymentStatus.PAID
                    || (ex.getStatus() == PaymentStatus.PENDING && !ex.isExpiredAt(Instant.now())));
            if (reusable) {
                return PaymentIntentResponse.of(ex);
            }
            if (ex.getStatus() == PaymentStatus.PENDING) {
                ex.markExpired(null);
                intents.saveAndFlush(ex);
            }
        }

        java.math.BigDecimal ratio = amount <= 0
                ? java.math.BigDecimal.ZERO
                : java.math.BigDecimal.valueOf(coinAmount)
                        .divide(java.math.BigDecimal.valueOf(amount), 6,
                                java.math.RoundingMode.HALF_UP);
        Instant expiresAt = ttl == null ? null : Instant.now().plus(ttl);
        PaymentIntent saved = intents.save(PaymentIntent.createMixed(userId, purpose, amount,
                coinAmount, cashAmount, ratio, currency, tokenGenerator.generate(), expiresAt));
        idempotency.store(idempotencyKey, saved.getId());
        return PaymentIntentResponse.of(saved);
    }

    /**
     * 按 token 作废一个待支付意图（Story 3.8：订单取消 / 支付超时）。
     *
     * <p>已终态则 no-op —— 取消一个已经付掉的意图不该把它改回失败。
     */
    @Transactional
    public void failByToken(String publicToken, String reason) {
        intents.findByPublicToken(publicToken).ifPresent(intent -> {
            if (intent.getStatus().isTerminal()) {
                return;
            }
            intent.markFailed(java.util.Map.of("reason", reason == null ? "CANCELLED" : reason));
            intents.saveAndFlush(intent);
            // 🔴 只在真的写了 FAILED 时发 —— 上面 terminal 短路那支是 no-op，发事件会灌水。
            publishFailed(intent);
        });
    }

    /** {@link #failChargeAttempt} 写入 meta 的 reason；{@link PaymentFailureCategory} 归入网关拒付。 */
    public static final String REASON_GATEWAY_CHARGE_FAILED = "GATEWAY_CHARGE_FAILED";

    /**
     * 向网关下单失败（超时 / 被拒 / 响应异常）后收口：该意图置 {@code FAILED}（reason
     * {@value #REASON_GATEWAY_CHARGE_FAILED}）并<b>保留这一行</b>。
     *
     * <p>🔴 为什么不能让它留在 {@code PENDING} 或整笔回滚（2026-09-21 生产 GemPay 超时事故）：
     * <ul>
     *   <li><b>回滚</b>：发给网关的 {@code request_id} 就是本意图的 {@code public_token}，行没了就再也
     *       对不上账——请求可能已经到了网关、建了单，我们这边却一无所知；</li>
     *   <li><b>留 PENDING</b>：各业务的幂等键 / 复用查询会把这张「没有二维码」的意图原样还给下一次重试，
     *       要么返回空载荷（App 没码可扫），要么拿<b>同一个 request_id</b> 再去下单——若首单其实已在网关
     *       落地，就会被当成重复单号（GemPay {@code P02}）永久拒掉。</li>
     * </ul>
     * 置 FAILED 后，下一次发起一律新建意图、新 {@code request_id}。
     *
     * <p>发 {@link PaymentIntentFailedEvent}（类别 {@code GATEWAY_DECLINED}）：这是用户实际看到的一次付款失败，
     * 不发的话电商漏斗要等 60 分钟后被扫描器记成「超时」，口径就错了。
     *
     * <p>已终态 / 已拿到网关单号（并发的另一请求已下单成功）→ no-op。
     */
    @Transactional
    public void failChargeAttempt(String publicToken) {
        intents.findByPublicToken(publicToken).ifPresent(intent -> {
            if (intent.getStatus().isTerminal() || intent.getGatewayRef() != null) {
                return;
            }
            intent.markFailed(java.util.Map.of("reason", REASON_GATEWAY_CHARGE_FAILED));
            intents.saveAndFlush(intent);
            publishFailed(intent);
        });
    }

    /**
     * 复用同档位未过期 PENDING 充值意图（V85，D-b）：同 {@code (user, purpose, channel, amount)} 且 PENDING、
     * 未过窗 → 返回它（供 topup 复用同一 QR，不重复下单）。命中但已过窗 → 懒过期置 EXPIRED 后返回空（触发新建）。
     */
    @Transactional
    public Optional<PaymentIntent> findReusablePending(long userId, PaymentPurpose purpose,
            PayChannel channel, long amount) {
        Optional<PaymentIntent> found = intents
                .findFirstByUserIdAndPurposeAndChannelAndAmountAndStatusOrderByCreatedAtDesc(
                        userId, purpose, channel, amount, PaymentStatus.PENDING);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        PaymentIntent intent = found.get();
        if (intent.isExpiredAt(Instant.now())) {
            intent.markExpired(null); // 懒过期：超窗即置 EXPIRED，不复用
            intents.saveAndFlush(intent);
            return Optional.empty();
        }
        if (intent.getGatewayRef() == null) {
            // 还没拿到网关单号 = 没有二维码可给（下单失败已由 failChargeAttempt 置 FAILED，走到这里只剩
            // 另一请求正在下单或进程中途退出）。复用它只会把空载荷还给 App → 不复用，新建一张。
            return Optional.empty();
        }
        return Optional.of(intent);
    }

    /**
     * 定时过期扫描（V85）：一批 PENDING 且 {@code expires_at < now} 的意图置 EXPIRED（懒过期的兜底，
     * 清理无人轮询的过窗充值）。逐笔独立、单笔失败不阻断。返回置 EXPIRED 的笔数。
     */
    @Transactional
    public int expireOverduePending(int limit) {
        int expired = 0;
        Instant now = Instant.now();
        for (PaymentIntent intent : intents.findByStatusAndExpiresAtBefore(PaymentStatus.PENDING,
                now, org.springframework.data.domain.PageRequest.of(0, Math.max(1, limit)))) {
            if (intent.getStatus() != PaymentStatus.PENDING) {
                continue; // 并发已推进
            }
            // 🔴 v1.4.2 shop Story 3.3（B1 路径②）：电商意图再判码失效时刻 —— 存量 / 改造前意图的
            //    expires_at 等于订单窗（≤ 60 分钟），而 GemPay 码要到出码 + 65 分钟才失效；码未失效前
            //    置 EXPIRED 会让 60~65 分钟内的付款变成迟到款。未到 → 本轮跳过（不置、不发事件）。
            //    其他 purpose 行为不变。
            if (intent.getPurpose() == PaymentPurpose.SHOP_ORDER && !isProviderExpired(intent, now)) {
                continue;
            }
            intent.markExpired(null);
            intents.save(intent);
            publishFailed(intent);
            expired++;
        }
        return expired;
    }

    /**
     * 发布 {@link PaymentIntentFailedEvent}（Story 1-2 AC8）。
     *
     * <p>🔴 <b>必须在 save 之后调</b>：{@link PaymentFailureCategory#of} 读的是意图的 status 与 meta，
     * 两者都要先定下来才算得对。
     *
     * <p>⚠️ <b>本类共有 10 个置终态的写入点，只有下面 5 个发事件</b>（Story 1-2 AC8 划定的范围，
     * 2026-09-22 加入 {@link #failChargeAttempt}）：
     * {@link #applyCallback} 的 {@code FAILED} / {@code EXPIRED} 两支、{@link #failByToken}、
     * {@link #expireOverduePending}、{@link #failChargeAttempt}。**不发**的另外几处及其理由：
     * <ul>
     *   <li>{@code createIntent} / {@code createMixedIntent} 的复用分支懒过期 —— 电商侧进不来
     *       （{@code requirePayable} 会先挡下过窗订单），且 {@code expireOverduePending}
     *       每 60 秒扫一遍会补上；</li>
     *   <li>{@link #findReusablePending} 的懒过期 —— 只服务 PAWCOIN_TOPUP 的复用查询；</li>
     *   <li>{@link #failPending} —— 唯一调用方是问诊线，非电商；</li>
     *   <li>🔴 {@link #statusOf} 的懒过期 —— <b>这一处的事件是永久丢失的</b>，与上面几处不同：
     *       {@link #expireOverduePending} 只扫 {@code status = PENDING}，补不回来；
     *       {@link #failByToken} 又因「已终态即 no-op」写不进去。今天不影响电商是因为
     *       App 轮询的是订单详情而不是意图状态端点。<b>谁要做充值 / 问诊的支付漏斗，
     *       第一件事就是补上这里</b>。</li>
     * </ul>
     * 所以电商漏斗今天不缺口径。但<b>这不是一条「所有失败都发事件」的不变式</b>——
     * 下一个要订阅本事件的业务线必须先自己核一遍这几处，别照着方法名想当然。
     */
    private void publishFailed(PaymentIntent intent) {
        events.publishEvent(new PaymentIntentFailedEvent(
                intent.getId(), intent.getPublicToken(), intent.getUserId(),
                intent.getPurpose(), intent.getChannel(), intent.getAmount(), intent.getCurrency(),
                PaymentFailureCategory.of(intent)));
    }

    /**
     * 回调/轮询单一收口（原名，保留为一行委托）。回调控制器、stag 支付模拟等既有调用方不改。
     *
     * @see #applyResult(PaymentCallback)
     */
    @Transactional
    public void applyCallback(PaymentCallback cb) {
        applyResult(cb);
    }

    /**
     * 意图状态推进的<b>单一收口</b>（v1.4.2 shop Story 3.3 AC5.6，架构 P-3）：回调、定时查单、「我已付款」、
     * 关单前查单四个入口全部经这里。幂等推进：Redis 前置短路 + 「已终态即返回」+ {@code gateway_ref}
     * 唯一约束库级兜底，双通道各到一次也只推进一次。
     *
     * <p>{@link GatewayStatus#PENDING} 不推进；无匹配意图仅记日志忽略（不因未知回调改任何状态）。
     *
     * <p>🔴 <b>关单后到账（I1，仅 {@code SHOP_ORDER}）</b>：电商意图已 FAILED / EXPIRED 又收到 PAID →
     * 照常转 PAID 并置 {@code paid_after_close}、发<b>现有</b> {@link PaymentIntentPaidEvent}（迟到款由
     * {@code ShopOrderPaidHandler} 记到原订单）。该分支<b>跳过</b> Redis 去重与终态守卫 —— 先到的 FAILED 回调
     * 已占了去重键，不跳过的话后到的 {@code Failure => Success} 会被吞掉，钱进来了系统不认。
     * 其他业务线终态后到账仍按原逻辑忽略。
     */
    @Transactional
    public void applyResult(PaymentCallback cb) {
        if (cb == null || cb.status() == GatewayStatus.PENDING) {
            return; // 未终态不推进
        }
        PaymentIntent intent = resolve(cb);
        if (intent == null) {
            log.warn("支付回调无匹配意图，忽略"); // 不打印 order_id/ref 外的正文/凭证
            return;
        }

        // 🔴 判定放在 Redis 去重之前，且只在 cb.status == PAID 时成立；FAILED / EXPIRED 回调对终态意图仍短路。
        boolean shopLate = intent.getPurpose() == PaymentPurpose.SHOP_ORDER
                && cb.status() == GatewayStatus.PAID
                && (intent.getStatus() == PaymentStatus.FAILED || intent.getStatus() == PaymentStatus.EXPIRED);
        if (shopLate) {
            intent.markPaidAfterClose(cb.gatewayRef(), cb.rawMeta());
            intents.saveAndFlush(intent); // @Version 乐观锁裁决并发
            events.publishEvent(new PaymentIntentPaidEvent(
                    intent.getId(), intent.getPublicToken(), intent.getUserId(),
                    intent.getPurpose(), intent.getChannel(), intent.getAmount(), intent.getCurrency()));
            log.info("电商意图关单后到账 intentId={}", intent.getId());
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
                publishFailed(intent);
            }
            case EXPIRED -> {
                intent.markExpired(cb.rawMeta());
                intents.saveAndFlush(intent);
                publishFailed(intent);
            }
            default -> { /* PENDING 已在入口挡下，不可达 */ }
        }
    }

    /** 码是否已失效：{@code now > COALESCE(provider_expires_at, created_at + 65 分钟)}（B1 统一判据的意图侧）。 */
    public static boolean isProviderExpired(PaymentIntent intent, Instant now) {
        Instant expiry = intent.effectiveProviderExpiry();
        return expiry != null && now.isAfter(expiry);
    }

    /** v1.4.2 shop Story 3.3：某电商订单的全部意图（按 id 升序）。 */
    @Transactional(readOnly = true)
    public java.util.List<PaymentIntent> findByShopOrderId(long shopOrderId) {
        return intents.findByShopOrderIdOrderByIdAsc(shopOrderId);
    }

    /** v1.4.2 shop Story 3.3 AC8.3：定时查单候选（≤ 100 笔）。 */
    @Transactional(readOnly = true)
    public java.util.List<PaymentIntent> findShopReconcileCandidates(Instant now) {
        return intents.findShopReconcileCandidates(now);
    }

    /**
     * 新建电商意图同事务绑定订单（v1.4.2 shop Story 3.3 AC2.2）：仅当 {@code shop_order_id} 为空时写。
     */
    @Transactional
    public void bindShopOrder(String publicToken, long shopOrderId) {
        intents.findByPublicToken(publicToken).ifPresent(intent -> {
            if (intent.getShopOrderId() == null) {
                intent.bindShopOrder(shopOrderId);
                intents.saveAndFlush(intent);
            }
        });
    }

    /** 按对外 token 读意图（供同模块下单/查询用；跨模块请走 DTO）。 */
    @Transactional(readOnly = true)
    public Optional<PaymentIntent> findByToken(String publicToken) {
        return intents.findByPublicToken(publicToken);
    }

    /** 按对外 token 批量读意图（v1.4.2 shop Story 6.2 订单列表 {@code canPay}，只读）。空入参不查库。 */
    @Transactional(readOnly = true)
    public List<PaymentIntent> findByTokens(java.util.Collection<String> publicTokens) {
        if (publicTokens == null || publicTokens.isEmpty()) {
            return List.of();
        }
        return intents.findByPublicTokenIn(publicTokens);
    }

    /**
     * 按内部 id 读意图（只读；业务行只存了 {@code payment_intent_id} 时用，如送礼订单的幂等重放 —— OCR #7：
     * 重放必须认订单<b>自己挂着的</b>那笔意图，不能靠幂等键映射再「取回」，映射丢失会建出第二笔意图）。
     */
    @Transactional(readOnly = true)
    public Optional<PaymentIntent> findById(long id) {
        return intents.findById(id);
    }

    /**
     * 支付状态轮询（Story 1.5）。<b>仅本人意图</b>——token 归属校验（{@code userId} 不符或不存在均
     * {@link AppException#notFound}，用 404 不用 403 以免泄漏他人 token 存在性）。
     *
     * <p><b>懒过期（V85）</b>：PENDING 且已过窗 → 就地置 EXPIRED 返回（轮询即见过期，不等定时扫描）。
     * 到账仍靠 1.3 回调，不主动向网关刷状态。
     */
    @Transactional
    public PaymentStatus statusOf(long userId, String publicToken) {
        PaymentIntent intent = intents.findByPublicToken(publicToken)
                .filter(i -> i.getUserId() != null && i.getUserId() == userId)
                .orElseThrow(() -> AppException.notFound("支付意图不存在"));
        // 🔴 v1.4.2 shop Story 3.3（B1 路径③）：电商意图在此<b>只读</b> —— 任何人用意图 token 调通用状态端点
        //    都能在订单窗（60 分钟）把它置 EXPIRED，而码 65 分钟才失效。电商意图的过期只经全局扫描（判码失效）
        //    与关单前查单。其他 purpose（充值 / 名额月租 / 送礼）依赖这里就地过期，行为不变。
        if (intent.getPurpose() != PaymentPurpose.SHOP_ORDER
                && intent.getStatus() == PaymentStatus.PENDING && intent.isExpiredAt(Instant.now())) {
            intent.markExpired(null);
            intents.saveAndFlush(intent);
        }
        return intent.getStatus();
    }

    /**
     * 收款创建成功后回填网关订单号 + 脱敏快照（Story 1.3 下单时调）。幂等：已回填则直接返回，
     * 避免同 {@code Idempotency-Key} 重放二次下单重复 charge。
     */
    @Transactional
    public void attachCharge(String publicToken, String gatewayRef, java.util.Map<String, Object> meta) {
        attachCharge(publicToken, gatewayRef, meta, null);
    }

    /**
     * 同上 + 写码失效时刻（v1.4.2 shop Story 3.3 AC2.3，<b>只有电商调用</b>）：在原有校验后执行
     * {@link PaymentIntent#markProviderIssued}，{@code provider_expires_at = expires_at = now + providerTtl}
     * （取网关返回之后的时刻，≥ GemPay 真实失效时刻，偏保守）。{@code providerTtl} 为 null 即三参版本行为。
     */
    @Transactional
    public void attachCharge(String publicToken, String gatewayRef, java.util.Map<String, Object> meta,
            Duration providerTtl) {
        PaymentIntent intent = intents.findByPublicToken(publicToken)
                .orElseThrow(() -> AppException.notFound("支付意图不存在"));
        if (intent.getGatewayRef() != null) {
            return; // 已下单，幂等短路
        }
        // 🔴 已终态（FAILED / EXPIRED / PAID）的意图绝不回填、调用方也拿不到二维码（2026-09-25 code review #1）。
        //    并发竞态：两个请求拿到同一张无网关单号的意图 —— 快的那个被网关判重复单号拒掉、经 failChargeAttempt
        //    置 FAILED；慢的那个随后拿到二维码。若照样回填并把码交给用户，用户扫码付了钱，到账回调却因
        //    「已终态」被丢弃，订单停在待支付直到超时取消 —— 收了钱不履约。
        //    这里抛出后调用方不会下发 payload；用户重试走新意图、新 request_id。网关侧那张单无人扫码，自然过期。
        //    与 failChargeAttempt 的并发写由 @Version 乐观锁裁决，只有一方落库。
        if (intent.getStatus().isTerminal()) {
            log.warn("attachCharge 拒绝：意图已终态 token={} status={}", publicToken, intent.getStatus());
            throw AppException.conflict("支付单已失效，请重新发起支付");
        }
        intent.attachGatewayRef(gatewayRef, meta);
        if (providerTtl != null) {
            intent.markProviderIssued(Instant.now().plus(providerTtl));
        }
        intents.saveAndFlush(intent);
    }

    /**
     * 用户主动取消联动（问诊 QRIS，Story 3.4 补丁）：该用户该 {@code purpose} 最新 PENDING 意图置 FAILED
     * （无则 no-op）。避免 cancel 后 PENDING 意图残留在后台支付列表。
     */
    @Transactional
    public void failPending(long userId, PaymentPurpose purpose) {
        intents.findFirstByUserIdAndPurposeAndStatusOrderByCreatedAtDesc(userId, purpose, PaymentStatus.PENDING)
                .ifPresent(intent -> {
                    intent.markFailed(java.util.Map.of("reason", "USER_CANCEL"));
                    intents.saveAndFlush(intent);
                });
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
