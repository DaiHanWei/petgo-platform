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
     * @param cardType    卡种 KTP / PASSPORT / KITAS / SIM / STUDENT（V1.3.4 ktp D-6）
     * @param cardVariant 学生卡配色，非学生卡 null
     * @param cardNo      身份码；V95 前老卡 null → 显示流水号补零（与 App 列表同口径）
     */
    public record KtpCard(long serialId, String petName, Instant unlockedAt, String cardType,
            String cardVariant, String cardNo) {

        /** 卡种名 i18n key 后缀：学生卡 {@code STUDENT.<配色>}，其余为卡种本身（D-6：8 款各显示自己的名称）。 */
        public String styleKey() {
            if ("STUDENT".equals(cardType)) {
                return "STUDENT." + (cardVariant == null ? "KLASIK" : cardVariant);
            }
            return cardType == null ? "KTP" : cardType;
        }

        /** 展示编号：优先身份码；V95 前老卡无身份码 → 流水号左补零到 4 位（App {@code id_card_page} 同口径）。 */
        public String displayNo() {
            if (cardNo != null && !cardNo.isEmpty()) {
                return cardNo;
            }
            String s = Long.toString(serialId);
            return s.length() >= 4 ? s : "0".repeat(4 - s.length()) + s;
        }
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
