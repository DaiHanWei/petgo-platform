package com.tailtopia.admin.payment.service;

import com.tailtopia.admin.payment.dto.AdminPaymentRow;
import com.tailtopia.pay.domain.PaymentIntent;
import com.tailtopia.pay.repository.PaymentIntentRepository;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 后台支付记录通用查询（Story 9.6，AB-8E）。按用户跨类型（VET_CONSULT/PAWCOIN_TOPUP/AI_UNLOCK/ID_HD）
 * 只读查 {@code payment_intents}。无敏感 PII（gateway_meta 已脱敏，本查询不返 meta）。
 */
@Service
public class AdminPaymentQueryService {

    private final PaymentIntentRepository intents;

    public AdminPaymentQueryService(PaymentIntentRepository intents) {
        this.intents = intents;
    }

    @Transactional(readOnly = true)
    public List<AdminPaymentRow> byUser(long userId) {
        return intents.findByUserIdOrderByCreatedAtDesc(userId).stream()
                .map(AdminPaymentQueryService::toRow).toList();
    }

    /** 默认视图：全部支付意图按 created_at 倒序分页（跨用户跨类型）。 */
    @Transactional(readOnly = true)
    public Page<AdminPaymentRow> recent(int page, int size) {
        return intents.findAll(PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt")))
                .map(AdminPaymentQueryService::toRow);
    }

    private static AdminPaymentRow toRow(PaymentIntent p) {
        return new AdminPaymentRow(p.getUserId(), p.getPublicToken(), p.getPurpose().name(),
                p.getChannel().name(), p.getAmount(), p.getCurrency(), p.getStatus().name(), p.getCreatedAt());
    }
}
