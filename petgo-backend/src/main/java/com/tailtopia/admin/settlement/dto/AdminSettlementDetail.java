package com.tailtopia.admin.settlement.dto;

import java.time.Instant;
import java.util.List;

/**
 * B13 月结抽屉（V1.3.0 Story 8.5 · AC3）：台账行 + **订单构成**。
 *
 * <p>🔴 订单构成是**读时按 (vetId, period 窗口) 重新聚合**出来的，不是存下来的快照：
 * `vet_settlements` 只存聚合值（单数 / 成交额 / 到手），没有明细表。
 * 口径必须与生成时逐字一致（{@code VetSettlementService}：COMPLETED、按
 * {@code session_ended_at} 归月、窗口 {@code [start, end)} 按 WIB 算）——
 * 差一点点，抽屉里列出的单数就与台账那一列对不上，而运营会以为账错了。
 *
 * @param vetName 兽医显示名。🔴 确认打款的二次确认必须复述**名字**而不是数字 id：
 *                财务同时开着好几笔月结、点错行时，一个他不认识的 id 挡不住任何东西。
 *                账号已被删/查不到时回退成 {@code #<id>}，不留空（空串会让弹窗读起来像坏了）。
 * @param orders 该月结包含的订单（订单号 / 成交额 / 兽医到手 / 会话结束时刻）
 */
public record AdminSettlementDetail(
        long id, long vetId, String vetName, String period, int orderCount, long grossAmount, long payoutAmount,
        String status, String paymentProof, Instant paidAt, Instant archivedAt, Instant generatedAt,
        List<OrderRow> orders) {

    /**
     * 构成里的一条订单。
     *
     * @param payout 兽医到手 —— 用的是**订单上的分成快照**，不是拿当前配置比例重算：
     *               分成比例改过之后重算会得到与已打款金额不同的数。
     */
    public record OrderRow(String orderToken, String displayNo, long amount, Long payout,
            Instant sessionEndedAt) {
    }

    public boolean pendingFinance() {
        return "PENDING_FINANCE".equals(status);
    }

    public boolean paid() {
        return "PAID".equals(status);
    }

    public boolean archived() {
        return "ARCHIVED".equals(status);
    }

    /** 归档要求凭证非空（现状仅服务层拒绝；抽屉据此前置禁用 + 提示）。 */
    public boolean hasProof() {
        return paymentProof != null && !paymentProof.isBlank();
    }
}
