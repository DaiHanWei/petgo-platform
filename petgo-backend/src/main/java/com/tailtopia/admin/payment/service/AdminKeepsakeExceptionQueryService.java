package com.tailtopia.admin.payment.service;

import com.tailtopia.order.dto.OrderDisplayNo;
import com.tailtopia.passport.service.BoardingPassDuplicateQuery;
import com.tailtopia.pay.domain.PaymentIntent;
import com.tailtopia.pay.dto.PaymentDisplayNo;
import com.tailtopia.pay.repository.PaymentIntentRepository;
import com.tailtopia.purchase.domain.KeepsakePurchase;
import com.tailtopia.purchase.domain.KeepsakePurchaseStatus;
import com.tailtopia.purchase.domain.KeepsakeSku;
import com.tailtopia.purchase.repository.KeepsakePurchaseRepository;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.EnumSet;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 后台「一次性解锁异常」只读查询（V1.3.2 Story 3.6 · AC5）：支付记录页只查 {@code payment_intents}，
 * PawCoin 购买没有意图、查不到 —— 这里直接看购买记录。经 purchase / passport 包的只读口取数。
 *
 * <p>只读：不提供退款；运营按既有人工退款流程处理。对模板只给 token 与展示号，不给内部 id。
 */
@Service
public class AdminKeepsakeExceptionQueryService {

    private static final DateTimeFormatter WIB_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.of("Asia/Jakarta"));
    private static final EnumSet<KeepsakePurchaseStatus> EXCEPTIONS =
            EnumSet.of(KeepsakePurchaseStatus.DUPLICATE_PAID, KeepsakePurchaseStatus.ORPHAN_PAID);
    private static final EnumSet<KeepsakePurchaseStatus> RECEIVED = EnumSet.of(KeepsakePurchaseStatus.PAID,
            KeepsakePurchaseStatus.DUPLICATE_PAID, KeepsakePurchaseStatus.ORPHAN_PAID);

    private final KeepsakePurchaseRepository purchases;
    private final PaymentIntentRepository intents;
    private final BoardingPassDuplicateQuery duplicates;

    public AdminKeepsakeExceptionQueryService(KeepsakePurchaseRepository purchases, PaymentIntentRepository intents,
            BoardingPassDuplicateQuery duplicates) {
        this.purchases = purchases;
        this.intents = intents;
        this.duplicates = duplicates;
    }

    @Transactional(readOnly = true)
    public Page<PurchaseRow> exceptionPurchases(int page, int size) {
        return purchases.findByStatusInOrderByPaidAtDescIdDesc(EXCEPTIONS, PageRequest.of(page, size)).map(this::row);
    }

    @Transactional(readOnly = true)
    public Page<BoardingRow> duplicateBoardingPasses(int page, int size) {
        List<BoardingRow> rows = duplicates.page(page, size).stream().map(r -> new BoardingRow(r.userId(),
                r.fromPlace(), r.toPlace(), wib(r.unlockedAt()), wib(r.supersededAt()),
                linkedPurchaseNo(r.unlockId()))).toList();
        return new PageImpl<>(rows, PageRequest.of(page, size), duplicates.count());
    }

    /** AC5.2：该行关联的 PAID 购买号；没有 PAID（极端：同行付过多次且都被判重）再退到任一已到账的。 */
    private String linkedPurchaseNo(long unlockId) {
        return purchases.findFirstBySkuAndRefIdAndStatusInOrderByIdDesc(KeepsakeSku.BOARDING_PASS, unlockId,
                        EnumSet.of(KeepsakePurchaseStatus.PAID))
                .or(() -> purchases.findFirstBySkuAndRefIdAndStatusInOrderByIdDesc(KeepsakeSku.BOARDING_PASS,
                        unlockId, RECEIVED))
                .map(AdminKeepsakeExceptionQueryService::orderNo).orElse(null);
    }

    private PurchaseRow row(KeepsakePurchase k) {
        PaymentIntent intent = k.getPaymentIntentId() == null ? null
                : intents.findById(k.getPaymentIntentId()).orElse(null);
        return new PurchaseRow(orderNo(k), k.getUserId(), k.getSku().name(),
                k.getPayChannel() == null ? null : k.getPayChannel().name(), k.getPriceIdr(), k.getStatus().name(),
                wib(k.getPaidAt()), intent == null ? null : PaymentDisplayNo.of(intent),
                intent == null ? null : intent.getPublicToken());
    }

    private static String orderNo(KeepsakePurchase k) {
        return OrderDisplayNo.of(OrderDisplayNo.keepsakePrefix(k.getSku()), k.getId(), k.getCreatedAt());
    }

    private static String wib(Instant t) {
        return t == null ? null : WIB_FMT.format(t) + " WIB";
    }

    /** 异常购买一行。{@code sku} 模板按 {@code admin.payments.purpose.*} 渲染（3-1 已有）。 */
    public record PurchaseRow(String orderNo, long userId, String sku, String channel, long amount, String status,
            String paidAtLabel, String intentDisplayNo, String intentToken) {
    }

    /** 登机牌重复解锁一行（不含宠物名）。 */
    public record BoardingRow(long userId, String fromPlace, String toPlace, String unlockedAtLabel,
            String supersededAtLabel, String purchaseNo) {
    }
}
