package com.tailtopia.admin.usermgmt.dto;

import java.time.Instant;
import java.util.List;

/**
 * 用户详情抽屉第六页签「已购解锁」（V1.3.2 后台 AB-23）。只读，四组按 SKU 分卡。
 *
 * <p>🔴 数据源是各业务表的<b>解锁状态</b>，不是支付记录：PawCoin 付的不建 payment_intent，读支付表会把它们漏掉。
 */
public record UserPurchasesView(List<KtpCard> ktpCards, List<PassportSnapshot> snapshots,
        List<BoardingPass> boardingPasses, List<TailsonalityResult> tailsonality) {

    /** 有没有合并后作废的登机牌（有才显示那行「已合并」释义）。 */
    public boolean hasSupersededBoardingPass() {
        return boardingPasses.stream().anyMatch(BoardingPass::superseded);
    }

    public boolean isEmpty() {
        return ktpCards.isEmpty() && snapshots.isEmpty() && boardingPasses.isEmpty() && tailsonality.isEmpty();
    }

    /**
     * KTP 卡高清。
     *
     * @param unlockedAt 真实付款时刻：QRIS = 对应支付单到账（PAID 的 {@code updated_at}，与看板 #15 同口径）；
     *                   PawCoin = 购买记录时刻（当场扣币）。V92 回填的老卡没有可用的付款记录 → null，页面显示「—」。
     */
    public record KtpCard(long serialId, String petName, Instant unlockedAt) {
    }

    /**
     * 护照快照。{@code version} = 该宠物<b>已付款</b>快照按付款先后编号（1 起、连续；未付款的不占号，2026-10-02 定）。
     */
    public record PassportSnapshot(String petName, int version, int stampCount, Instant unlockedAt) {
    }

    /** 登机牌。{@code superseded} = 场所合并后作废的那张（为同一地方付过两次），仍列出并标「已合并」（2026-10-02 定）。 */
    public record BoardingPass(String petName, String placeName, Instant unlockedAt, boolean superseded) {
    }

    /** Tailsonality 结果。{@code roleCode} = 类型码 + 能量档，如 {@code ENTJ-H}。 */
    public record TailsonalityResult(String petName, String roleCode, Instant unlockedAt) {
    }
}
