package com.tailtopia.profile.slot;

import com.tailtopia.account.repository.AccountDeletionRepository;
import com.tailtopia.auth.domain.User;
import com.tailtopia.auth.service.AccountQueryService;
import com.tailtopia.pay.domain.PawCoinTxnType;
import com.tailtopia.pay.domain.PayChannel;
import com.tailtopia.pay.domain.PaymentFailureCategory;
import com.tailtopia.pay.domain.PaymentIntent;
import com.tailtopia.pay.domain.PaymentPurpose;
import com.tailtopia.pay.domain.PaymentStatus;
import com.tailtopia.pay.dto.PaymentIntentResponse;
import com.tailtopia.pay.repository.PaymentIntentRepository;
import com.tailtopia.pay.service.PawCoinWalletService;
import com.tailtopia.pay.service.PaymentIntentService;
import com.tailtopia.profile.slot.domain.PetSlotPurchase;
import com.tailtopia.profile.slot.domain.PetSlotPurchaseKind;
import com.tailtopia.profile.slot.domain.PetSlotPurchaseStatus;
import com.tailtopia.profile.slot.domain.PetSlotRental;
import com.tailtopia.profile.slot.dto.PetSlotPurchaseResponse;
import com.tailtopia.profile.slot.dto.PetSlotPurchaseStatusView;
import com.tailtopia.profile.slot.repository.PetSlotPurchaseRepository;
import com.tailtopia.profile.slot.repository.PetSlotRentalRepository;
import com.tailtopia.shared.error.AppException;
import com.tailtopia.shared.pay.ChargeRequest;
import com.tailtopia.shared.pay.ChargeResult;
import com.tailtopia.shared.pay.PayException;
import com.tailtopia.shared.pay.PaymentGateway;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 宠物名额月租：新租 / 续租下单与 PawCoin 当场发放（V1.4.0 multi-pet Story 2.2 · AD-6 · FR-146.2），结构照
 * {@code KeepsakePurchaseService}（不复用该类：它的 SKU / {@code KeepsakeRef} 模型不适用）。QRIS 到账发放见 {@link PetSlotPaidHandler}。
 *
 * <h2>🔴 幂等键按渠道加后缀</h2>
 * 服务端键 {@code PET_SLOT_RENT:{userId}:{kind}:{clientKey}:{channel}}。{@code IdempotencyService} 是全局 {@code idem:} 命名空间：
 * 同一串给 {@code createIntent} 与 {@code debit}，「先开过 QRIS 再改用 PawCoin」会被 {@code debit} 判成重放、不扣币，
 * 名额却发了 = 免费拿到。PawCoin 购买行 {@code idem_key} = 服务端键；QRIS 购买行 {@code idem_key} = 服务端键 + {@code :<intentId>}
 * （同键重开换了新意图时，旧行置 EXPIRED、另起新行 —— 绝不改挂，旧二维码若仍付成功也能按 {@code payment_intent_id} 找到旧行发放，
 * code review #3）。
 *
 * <h2>🔴 锁序（code review #4）</h2>
 * QRIS：先 {@code createIntent}（可能懒过期并改写旧意图行）<b>再</b> {@link PetSlotLock#lockUser}，与到账
 * {@code applyCallback}（意图行 → 账号锁）同序，避免互等死锁。PawCoin 不碰意图行，进入即加锁。
 *
 * <h2>🔴 封顶 5（code review #2）</h2>
 * 可租数 = 5 − 当前名额 − 仍可能到账的待付新租数（含续租到账时因名额已过期而改为新建的数），在账号锁内判；到账时
 * {@link PetSlotGranter} 再兜一次（超了不发、记 OVERPAID）。
 *
 * <p>价格每次下单实时读 {@link PetSlotService#unitPrice()} 并快照进购买行；改价只影响之后的新单，已生成的 PENDING 单按原价付。
 * 🔴 本类不写任何成长值逻辑（① 经 {@code PawCoinSpentEvent} / {@code PaymentIntentPaidEvent} 自行处理）。
 *
 * <h2>运营补发（Story 4.3 · {@link #grantByAdmin}）</h2>
 * 不经支付：同一把账号锁 → 锁内 {@link PetSlotService#quotaOf} 判 5 封顶 → ADMIN_GRANT 购买行 → 经 {@link PetSlotGranter}
 * 发放（{@code source = ADMIN_GRANT}）→ {@link PetSlotStateService#reconcile}（RENTAL_ADDED，只恢复不降级）。
 * 审计由后台调用方在同一事务里写（{@code AdminPetSlotGrantService}，本包不依赖后台审计）。
 */
@Service
public class PetSlotPurchaseService {

    /** QRIS 付款窗（照 {@code KeepsakePurchaseService.PAY_WINDOW}）。 */
    static final Duration PAY_WINDOW = Duration.ofMinutes(60);
    /** PawCoin 流水 {@code refType}（① 1.3 口径表约定）；{@code refId} = 购买行 id。 */
    public static final String REF_TYPE = "PET_SLOT_RENT";
    /** 客户端 {@code Idempotency-Key} 最大长度（服务端键前缀 + 后缀 + 意图 id 须装进 {@code idem_key VARCHAR(160)}）。 */
    static final int MAX_CLIENT_KEY_LENGTH = 80;
    private static final String CURRENCY = "IDR";
    /** 运营补发 {@code idem_key} 前缀（Story 4.3 · AC5.2③）。 */
    public static final String ADMIN_GRANT_KEY_PREFIX = "ADMIN_GRANT:";
    /** 运营补发天数上限（Story 4.3 需拍板 #1：防手误填成 3650）。 */
    public static final int ADMIN_GRANT_MAX_DAYS = 365;
    /** 运营补发原因最大长度（= {@code pet_slot_purchases.reason VARCHAR(200)}）。 */
    public static final int ADMIN_GRANT_MAX_REASON = 200;
    /**
     * 运营补发幂等 token 形状：页面渲染的 UUID（36 位）；放宽到 ≤ 64 位 {@code [A-Za-z0-9-]}。
     * {@code "ADMIN_GRANT:" + token} 须装进 {@code idem_key VARCHAR(160)}（code review #4：超长曾 500）。
     */
    static final java.util.regex.Pattern ADMIN_GRANT_TOKEN = java.util.regex.Pattern.compile("[A-Za-z0-9-]{1,64}");
    private static final Logger log = LoggerFactory.getLogger(PetSlotPurchaseService.class);

    private final PetSlotPurchaseRepository purchases;
    private final PetSlotRentalRepository rentals;
    private final PetSlotService slots;
    private final PetSlotStateService state;
    private final PetSlotLock lock;
    private final PaymentIntentService paymentIntents;
    private final PaymentIntentRepository intentRepo;
    private final PawCoinWalletService wallet;
    private final PaymentGateway gateway;
    private final PetSlotGranter granter;
    private final PetSlotTokenGenerator tokens;
    private final AccountQueryService accounts;
    private final AccountDeletionRepository deletions;
    private final Clock clock;

    @Autowired
    public PetSlotPurchaseService(PetSlotPurchaseRepository purchases, PetSlotRentalRepository rentals,
            PetSlotService slots, PetSlotStateService state, PetSlotLock lock, PaymentIntentService paymentIntents,
            PaymentIntentRepository intentRepo, PawCoinWalletService wallet, PaymentGateway gateway,
            PetSlotGranter granter, PetSlotTokenGenerator tokens, AccountQueryService accounts,
            AccountDeletionRepository deletions) {
        this(purchases, rentals, slots, state, lock, paymentIntents, intentRepo, wallet, gateway, granter, tokens,
                accounts, deletions, Clock.systemUTC());
    }

    PetSlotPurchaseService(PetSlotPurchaseRepository purchases, PetSlotRentalRepository rentals,
            PetSlotService slots, PetSlotStateService state, PetSlotLock lock, PaymentIntentService paymentIntents,
            PaymentIntentRepository intentRepo, PawCoinWalletService wallet, PaymentGateway gateway,
            PetSlotGranter granter, PetSlotTokenGenerator tokens, AccountQueryService accounts,
            AccountDeletionRepository deletions, Clock clock) {
        this.purchases = purchases;
        this.rentals = rentals;
        this.slots = slots;
        this.state = state;
        this.lock = lock;
        this.paymentIntents = paymentIntents;
        this.intentRepo = intentRepo;
        this.wallet = wallet;
        this.gateway = gateway;
        this.granter = granter;
        this.tokens = tokens;
        this.accounts = accounts;
        this.deletions = deletions;
        this.clock = clock;
    }

    /** 服务端幂等键（见类注释：按渠道加后缀）。 */
    static String idempotencyKey(long userId, PetSlotPurchaseKind kind, String clientKey, PayChannel channel) {
        return REF_TYPE + ":" + userId + ":" + kind.name() + ":" + clientKey + ":" + channel.name();
    }

    /** QRIS 购买行的 {@code idem_key}：服务端键 + 意图 id（一个意图一行；同键重开另起一行）。 */
    static String qrisRowKey(String key, long intentId) {
        return key + ":" + intentId;
    }

    // ── 新租 ────────────────────────────────────────────────────────────────

    /**
     * 新租 {@code quantity} 个名额（AC4）。{@code quantity} 不在 {@code [1, 可租数]} → 409 {@code pet-slot-rent-unavailable}
     * （名额 5 时一律拒）；MIXED → 422。
     */
    // noRollbackFor：GemPay 下单失败时 failChargeAttempt 已把意图置 FAILED，这一行必须随事务提交留档（照 KeepsakePurchaseService.start）。
    @Transactional(noRollbackFor = PayException.class)
    public PetSlotPurchaseResponse rent(long userId, int quantity, PayChannel channel, String clientIdemKey) {
        requireClientKey(clientIdemKey);
        requireSingleChannel(channel);
        String key = idempotencyKey(userId, PetSlotPurchaseKind.RENT, clientIdemKey, channel);
        if (channel == PayChannel.PAWCOIN) {
            lock.lockUser(userId);
            Optional<PetSlotPurchase> replay = settledReplay(key);
            if (replay.isPresent()) {
                return PetSlotPurchaseResponse.unlocked(replay.get().getPublicToken());
            }
            requireRentable(userId, quantity, -1L);
            long unitPrice = slots.unitPrice();
            return payWithPawcoin(userId, PetSlotPurchaseKind.RENT, quantity, unitPrice, unitPrice * quantity, key,
                    null);
        }
        // QRIS：意图先于账号锁（锁序见类注释）。无锁预检只为不白建意图，权威判定在锁内。
        if (quantity < 1) {
            throw AppException.petSlotRentUnavailable(Math.max(0, PetSlotService.MAX_PETS - slots.quotaOf(userId).total()));
        }
        long unitPrice = slots.unitPrice();
        PaymentIntent intent = openIntent(userId, unitPrice * quantity, key);
        if (intent.getStatus() == PaymentStatus.PAID) {
            return alreadyPaid(intent);
        }
        lock.lockUser(userId);
        Optional<PetSlotPurchase> same = purchases.findByPaymentIntentId(intent.getId());
        if (same.isEmpty()) {
            requireRentable(userId, quantity, intent.getId());
        }
        return qrisRow(userId, PetSlotPurchaseKind.RENT, quantity, unitPrice, key, null, intent, same);
    }

    // ── 续租 ────────────────────────────────────────────────────────────────

    /**
     * 续租所选名额（AC5）：逐个按 {@code public_token} + 归属查（非本人 / 不存在 → 404）；须生效中，否则 409
     * {@code pet-slot-renew-invalid}（已过期名额不能续，走新租）；个数 ≤ max(0, 5 − 1 − 等级名额)，否则 409
     * {@code pet-slot-rent-unavailable}。一次付款，所选名额各 +30 天。
     */
    @Transactional(noRollbackFor = PayException.class)
    public PetSlotPurchaseResponse renew(long userId, List<String> rentalTokens, PayChannel channel,
            String clientIdemKey) {
        requireClientKey(clientIdemKey);
        requireSingleChannel(channel);
        List<String> requested = requireTokens(rentalTokens);
        String key = idempotencyKey(userId, PetSlotPurchaseKind.RENEW, clientIdemKey, channel);
        if (channel == PayChannel.PAWCOIN) {
            lock.lockUser(userId);
            Optional<PetSlotPurchase> replay = settledReplay(key);
            if (replay.isPresent()) {
                return PetSlotPurchaseResponse.unlocked(replay.get().getPublicToken());
            }
            List<Long> ids = resolveRenewable(userId, requested);
            long unitPrice = slots.unitPrice();
            return payWithPawcoin(userId, PetSlotPurchaseKind.RENEW, ids.size(), unitPrice, unitPrice * ids.size(),
                    key, ids);
        }
        resolveRenewable(userId, requested); // 无锁预检（不白建意图）
        long unitPrice = slots.unitPrice();
        PaymentIntent intent = openIntent(userId, unitPrice * requested.size(), key);
        if (intent.getStatus() == PaymentStatus.PAID) {
            return alreadyPaid(intent);
        }
        lock.lockUser(userId);
        Optional<PetSlotPurchase> same = purchases.findByPaymentIntentId(intent.getId());
        List<Long> ids = resolveRenewable(userId, requested);
        return qrisRow(userId, PetSlotPurchaseKind.RENEW, ids.size(), unitPrice, key, ids, intent, same);
    }

    // ── 购买状态（App 轮询） ─────────────────────────────────────────────────

    /**
     * 购买单状态（AC8.5）；非本人 → 404。⚠️ {@code PaymentIntentService.statusOf} 的懒过期<b>不发</b>失败事件 →
     * 这里读意图状态时自愈：意图已 EXPIRED / FAILED 而购买行仍 PENDING → 同步置终态（AC7.2）。
     */
    @Transactional
    public PetSlotPurchaseStatusView status(long userId, String purchaseToken) {
        PetSlotPurchase row = purchases.findByPublicTokenAndUserId(purchaseToken, userId)
                .orElseThrow(() -> AppException.notFound("购买记录不存在"));
        if (row.getStatus() == PetSlotPurchaseStatus.PENDING && row.getPaymentIntentId() != null) {
            intentRepo.findById(row.getPaymentIntentId()).ifPresent(intent -> {
                PaymentStatus s = paymentIntents.statusOf(userId, intent.getPublicToken()); // PENDING 过窗 → 就地 EXPIRED
                if (s == PaymentStatus.EXPIRED || s == PaymentStatus.FAILED) {
                    row.markStatus(failureStatusOf(intent));
                    purchases.save(row);
                }
            });
        }
        Instant expiresAt = row.getStatus() == PetSlotPurchaseStatus.PAID ? latestExpiry(row) : null;
        return new PetSlotPurchaseStatusView(row.getStatus().name(), expiresAt);
    }

    // ── 运营补发（Story 4.3） ─────────────────────────────────────────────────

    /**
     * 运营补发 1 个名额（Story 4.3 · AC5）。同一事务、按序：
     * <ol>
     *   <li>校验：{@code 1 ≤ days ≤ 365}、原因非空且 ≤ 200、{@code grantToken} 非空 → 否则 422；用户不存在 → 404，已注销 → 422；</li>
     *   <li>账号锁 {@link PetSlotLock#lockUser}（与建档 / 租用 / reconcile 同一把）；同 {@code idem_key} 已有行 → 重放
     *       （不再建名额、不 reconcile，{@code replayed = true}）；锁内 {@code quotaOf} 总数已 = 5 → 422；</li>
     *   <li>插 ADMIN_GRANT 购买行（金额 0、无渠道 / 意图、带操作人与原因）；</li>
     *   <li>经 {@link PetSlotGranter} 发放 1 行名额（{@code source = ADMIN_GRANT}、{@code expires_at = now + days}），
     *       购买行回填 {@code rental_ids}、置 PAID；</li>
     *   <li>{@link PetSlotStateService#reconcile}（{@code RENTAL_ADDED}：有只读宠物时按顺序恢复，AD-4）。</li>
     * </ol>
     * 🔴 不收费：不调钱包、不建支付意图、不发 {@code PawCoinSpentEvent}（因而不产生成长值）。审计由调用方在同一事务里写。
     * 日志只记 userId / days。
     */
    @Transactional
    public AdminSlotGrantResult grantByAdmin(AdminSlotGrantCommand cmd) {
        String reason = cmd.reason() == null ? "" : cmd.reason().trim();
        if (cmd.days() < 1 || cmd.days() > ADMIN_GRANT_MAX_DAYS) {
            throw AppException.validation("补发天数须为 1–365 的整数").code("admin.err.petSlot.daysInvalid");
        }
        if (reason.isEmpty()) {
            throw AppException.validation("请填写补发原因").code("admin.err.petSlot.reasonRequired");
        }
        if (reason.length() > ADMIN_GRANT_MAX_REASON) {
            throw AppException.validation("补发原因不能超过 200 字").code("admin.err.petSlot.reasonTooLong");
        }
        if (cmd.grantToken() == null || !ADMIN_GRANT_TOKEN.matcher(cmd.grantToken().trim()).matches()) {
            // 空 / 超长 / 非法字符一律 422（不让超长 token 撞 idem_key VARCHAR(160) 冒成 500）。
            throw AppException.validation("缺少幂等标识，请刷新页面后重试").code("admin.err.petSlot.missingToken");
        }
        long userId = cmd.userId();
        User target = accounts.findUserById(userId)
                .orElseThrow(() -> AppException.notFound("用户不存在").code("admin.err.user.notFound"));
        // 🔴 注销是 @Async 级联、deletedAt 最后才落：受理后（account_deletions 已有行）即视为注销中，同样拒绝
        //    （与抽屉 readOnly = deleted || deletionPending 同一口径；code review #2）。
        if (target.getDeletedAt() != null || deletions.findByUserId(userId).isPresent()) {
            throw AppException.validation("该账号已注销，不可补发").code("admin.err.petSlot.userDeleted");
        }
        String key = adminGrantKey(cmd.grantToken());

        lock.lockUser(userId);
        Optional<PetSlotPurchase> replay = purchases.findByIdemKey(key);
        if (replay.isPresent()) {
            PetSlotPurchase prior = replay.get();
            if (prior.getUserId() != userId || prior.getKind() != PetSlotPurchaseKind.ADMIN_GRANT) {
                // 同一 token 被拿去给另一个账号提交：不认作重放（也绝不给第二个人发名额），请刷新拿新 token。
                throw AppException.validation("缺少幂等标识，请刷新页面后重试").code("admin.err.petSlot.missingToken");
            }
            return new AdminSlotGrantResult(latestExpiry(prior), true, prior.getDays());
        }
        if (slots.quotaOf(userId).total() >= PetSlotService.MAX_PETS) {
            throw AppException.validation("该用户已有 5 个名额，不能再补发").code("admin.err.petSlot.limitReached");
        }

        Instant now = Instant.now(clock);
        PetSlotPurchase row;
        try {
            row = purchases.saveAndFlush(PetSlotPurchase.adminGrant(tokens.generate(), userId, cmd.days(), key,
                    cmd.operatorAdminId(), reason, now));
        } catch (DataIntegrityViolationException e) {
            // 同一 token 被并发提交给两个账号（两把不同的账号锁，互不串行）：后到者撞 idem_key 唯一键 → 409，整笔回滚。
            throw AppException.conflict("缺少幂等标识，请刷新页面后重试").code("admin.err.petSlot.missingToken");
        }
        PetSlotGranter.Result result = granter.grant(row, now);
        if (!result.granted()) {
            // 锁内已按 quotaOf 判过封顶，正常不可达；抛错整笔回滚（购买行不留）。
            throw AppException.validation("该用户已有 5 个名额，不能再补发").code("admin.err.petSlot.limitReached");
        }
        row.settle(PetSlotPurchaseStatus.PAID, now);
        row.setRentalIds(result.rentalIds());
        purchases.save(row);
        state.reconcile(userId, result.trigger());
        log.info("运营补发宠物名额 userId={} days={}", userId, cmd.days());
        return new AdminSlotGrantResult(now.plus(Duration.ofDays(cmd.days())), false, cmd.days());
    }

    /** 运营补发的 {@code idem_key}：{@code "ADMIN_GRANT:" + token}（唯一键兜底，Story 4.3 · AC5.2③）。 */
    static String adminGrantKey(String grantToken) {
        return ADMIN_GRANT_KEY_PREFIX + grantToken.trim();
    }

    // ── 内部 ────────────────────────────────────────────────────────────────

    /** 锁内判可租数：5 − 当前名额 − 仍可能到账的其它待付单会新增的名额（排除本意图）。 */
    private void requireRentable(long userId, int quantity, long excludeIntentId) {
        PetSlotQuota quota = slots.quotaOf(userId);
        Instant now = Instant.now(clock);
        long pending = purchases.sumOpenRentSlots(userId, now, excludeIntentId)
                + purchases.countOpenRenewConversions(userId, now, excludeIntentId);
        long rentable = PetSlotService.MAX_PETS - quota.total() - pending;
        if (quantity < 1 || quantity > rentable) {
            throw AppException.petSlotRentUnavailable((int) Math.max(0, rentable));
        }
    }

    /** 续租目标：归属 404 → 生效中 409 → 个数上限 409。返回所选名额 id（按请求顺序）。 */
    private List<Long> resolveRenewable(long userId, List<String> requested) {
        Instant now = Instant.now(clock);
        List<Long> ids = new ArrayList<>();
        for (String token : requested) {
            PetSlotRental r = rentals.findByPublicTokenAndUserId(token, userId)
                    .orElseThrow(() -> AppException.notFound("名额不存在"));
            if (!r.isLiveAt(now)) {
                throw AppException.petSlotRenewInvalid("所选名额已过期，请重新租用");
            }
            ids.add(r.getId());
        }
        PetSlotQuota quota = slots.quotaOf(userId);
        int renewableMax = PetSlotService.renewableMax(quota);
        if (ids.size() > renewableMax) {
            throw AppException.petSlotRenewOverLimit(Math.max(0, PetSlotService.MAX_PETS - quota.total()), renewableMax);
        }
        return ids;
    }

    private PetSlotPurchaseResponse payWithPawcoin(long userId, PetSlotPurchaseKind kind, int quantity, long unitPrice,
            long total, String key, List<Long> rentalIds) {
        Instant now = Instant.now(clock);
        // 先落 PENDING 拿 id 作 refId（同一事务：余额不足 / 任何失败一起回滚）。
        PetSlotPurchase row = purchases.saveAndFlush(PetSlotPurchase.pending(tokens.generate(), userId, kind, quantity,
                unitPrice, total, PayChannel.PAWCOIN, null, key, rentalIds, now));
        // 余额不足 → debit 抛 pawcoin-insufficient（409）→ 整事务回滚（购买行、名额都不留）。
        wallet.debit(userId, total, PawCoinTxnType.SPEND, REF_TYPE, row.getId(), key);
        // PawCoin 同步成交：发放失败原样抛 → 整笔回滚（扣币一起撤），不留「扣了币、没发名额」的单。
        PetSlotGranter.Result result = granter.grant(row, now);
        if (!result.granted()) {
            throw AppException.petSlotRentUnavailable(0); // 锁内已判过可租数，正常不可达；拒而不扣
        }
        row.settle(PetSlotPurchaseStatus.PAID, now);
        row.setRentalIds(result.rentalIds());
        purchases.save(row);
        state.reconcile(userId, result.trigger());
        return PetSlotPurchaseResponse.unlocked(row.getPublicToken());
    }

    private PaymentIntent openIntent(long userId, long total, String key) {
        PaymentIntentResponse intentResp = paymentIntents.createIntent(userId, PaymentPurpose.PET_SLOT_RENT,
                PayChannel.QRIS, total, CURRENCY, key, PAY_WINDOW);
        return paymentIntents.findByToken(intentResp.token())
                .orElseThrow(() -> AppException.notFound("支付意图不存在"));
    }

    /** createIntent 对 PAID 直接返回既有意图：这笔已付过。发放以购买行为准。 */
    private PetSlotPurchaseResponse alreadyPaid(PaymentIntent intent) {
        return purchases.findByPaymentIntentId(intent.getId())
                .filter(p -> p.getStatus() == PetSlotPurchaseStatus.PAID)
                .map(p -> PetSlotPurchaseResponse.unlocked(p.getPublicToken()))
                .orElseThrow(() -> AppException.conflict("该订单已付款，正在处理"));
    }

    /**
     * QRIS 购买行：本意图已有行 → 复用（同键重放出同一张码）；否则把同键下其它意图的 PENDING 旧行置 EXPIRED
     * （旧意图已被 createIntent 懒过期，不发失败事件），再按本次快照另起一行（{@code idem_key} 带意图 id）。
     */
    private PetSlotPurchaseResponse qrisRow(long userId, PetSlotPurchaseKind kind, int quantity, long unitPrice,
            String key, List<Long> rentalIds, PaymentIntent intent, Optional<PetSlotPurchase> same) {
        PetSlotPurchase row = same.orElse(null);
        if (row == null) {
            String prefix = key + ":";
            for (PetSlotPurchase old : purchases.findByUserIdAndStatus(userId, PetSlotPurchaseStatus.PENDING)) {
                String k = old.getIdemKey();
                if (k.startsWith(prefix) && k.substring(prefix.length()).chars().allMatch(Character::isDigit)
                        && !Long.valueOf(intent.getId()).equals(old.getPaymentIntentId())) {
                    old.markStatus(PetSlotPurchaseStatus.EXPIRED);
                    purchases.saveAndFlush(old);
                }
            }
            row = purchases.saveAndFlush(PetSlotPurchase.pending(tokens.generate(), userId, kind, quantity, unitPrice,
                    intent.getAmount(), PayChannel.QRIS, intent.getId(), qrisRowKey(key, intent.getId()), rentalIds,
                    Instant.now(clock)));
        }
        String payload;
        try {
            payload = ensureCharge(intent);
        } catch (PayException e) {
            // stag 复验：网关下单失败（如 stag GemPay 恒 P04）原先原样抛出 → 500 + 每次一条 ERROR。
            // failChargeAttempt 已把意图置 FAILED（同步失败监听把本行置 FAILED），这里收口成正常响应：
            // unlocked=false 且无载荷 → App 提示「Pembayaran gagal. Coba lagi.」；同键重试时 createIntent
            // 不复用 FAILED 意图、另起新意图与新行，第 2 次点击即真正重下。事务照常提交（两行都留档、无半成品）。
            // 只接 PayException；意图没被置 FAILED（并发的另一请求已拿到网关单号）→ 照旧抛出。
            if (intent.getStatus() != PaymentStatus.FAILED) {
                throw e;
            }
            if (row.getStatus() == PetSlotPurchaseStatus.PENDING) {
                row.markStatus(PetSlotPurchaseStatus.FAILED);
                purchases.saveAndFlush(row);
            }
            log.warn("pet slot qris charge failed purchaseId={} intentId={} reason={}", row.getId(), intent.getId(),
                    e.getMessage());
            return PetSlotPurchaseResponse.chargeFailed(row.getPublicToken());
        }
        return PetSlotPurchaseResponse.paymentRequired(PaymentIntentResponse.of(intent), payload,
                row.getPublicToken());
    }

    /**
     * 首次下单向网关取二维码载荷；幂等重放返回既有载荷（照 {@code KeepsakePurchaseService.ensureCharge} 的私有副本）。
     * 失败时 {@code failChargeAttempt} 留档后原样抛出，由 {@link #qrisRow} 收口为「无载荷」正常响应。
     */
    private String ensureCharge(PaymentIntent entity) {
        if (entity.getGatewayRef() == null) {
            ChargeResult charge;
            try {
                charge = gateway.createCharge(new ChargeRequest(entity.getPublicToken(), entity.getAmount(), CURRENCY,
                        PayChannel.QRIS.name(), entity.getPurpose().name()));
            } catch (RuntimeException e) {
                paymentIntents.failChargeAttempt(entity.getPublicToken());
                throw e;
            }
            Map<String, Object> meta = new LinkedHashMap<>();
            if (charge.rawMeta() != null) {
                meta.putAll(charge.rawMeta());
            }
            if (charge.payload() != null) {
                meta.put("payload", charge.payload());
            }
            paymentIntents.attachCharge(entity.getPublicToken(), charge.gatewayRef(), meta);
            return charge.payload();
        }
        Map<String, Object> savedMeta = entity.getGatewayMeta();
        return savedMeta == null ? null : (String) savedMeta.get("payload");
    }

    /** PawCoin 同键已完成的购买（重放返回同一结果，不再校验可租数——已发放的名额本就计入了名额）。 */
    private Optional<PetSlotPurchase> settledReplay(String key) {
        return purchases.findByIdemKey(key).filter(p -> p.getStatus() == PetSlotPurchaseStatus.PAID);
    }

    /** 意图失败类别 → 购买行终态（照 {@code PetSlotPaymentFailedHandler}）：超时类 → EXPIRED，其余 → FAILED。 */
    static PetSlotPurchaseStatus failureStatusOf(PaymentIntent intent) {
        return PaymentFailureCategory.of(intent) == PaymentFailureCategory.EXPIRED
                ? PetSlotPurchaseStatus.EXPIRED
                : PetSlotPurchaseStatus.FAILED;
    }

    private Instant latestExpiry(PetSlotPurchase row) {
        Instant latest = null;
        for (PetSlotRental r : rentals.findAllById(row.rentalIdList())) {
            if (latest == null || r.getExpiresAt().isAfter(latest)) {
                latest = r.getExpiresAt();
            }
        }
        return latest;
    }

    private static void requireClientKey(String clientKey) {
        if (clientKey == null || clientKey.isBlank()) {
            throw AppException.validation("资金下单必须携带 Idempotency-Key 头");
        }
        if (clientKey.length() > MAX_CLIENT_KEY_LENGTH) {
            throw AppException.validation("Idempotency-Key 过长（最多 " + MAX_CLIENT_KEY_LENGTH + " 个字符）");
        }
    }

    private static void requireSingleChannel(PayChannel channel) {
        if (channel == null) {
            throw AppException.validation("缺少支付方式");
        }
        if (channel == PayChannel.MIXED) {
            // 🔴 虚拟商品【恒单渠道】：MIXED 只用于电商实物订单。显式拒绝（照 KeepsakePurchaseService.start）。
            throw AppException.validation("该商品不支持混合支付");
        }
    }

    /** 续租名额列表：非空、无 null / 空串、不重复、≤ 5（防超长列表在持锁期间逐条查库）。 */
    private static List<String> requireTokens(List<String> rentalTokens) {
        if (rentalTokens == null || rentalTokens.isEmpty()) {
            throw AppException.validation("请选择要续租的名额");
        }
        if (rentalTokens.size() > PetSlotService.MAX_PETS) {
            throw AppException.validation("rentalTokens 最多 " + PetSlotService.MAX_PETS + " 个");
        }
        Set<String> distinct = new LinkedHashSet<>();
        for (String t : rentalTokens) {
            if (t == null || t.isBlank()) {
                throw AppException.validation("rentalTokens 不能含空值");
            }
            if (!distinct.add(t)) {
                throw AppException.validation("rentalTokens 不能重复");
            }
        }
        return List.copyOf(distinct);
    }
}
