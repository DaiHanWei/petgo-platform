package com.tailtopia.analytics.rollup;

/**
 * 「运营位是否推广 Toko」判定（analytics-pipeline Story 4.4 T3，FR-17 第 3 条）。纯函数，按<b>计算当时</b>的业务库配置判定。
 *
 * <ul>
 *   <li>{@code ops_popup} / 运营推送（{@code ops_notification_campaigns}，与弹窗同一跳转口径）：{@code target_type} 为
 *       {@code PRODUCT} / {@code CATEGORY}，或 {@code APP_PAGE} 且 {@code target_value = 'TOKO'} → true；
 *       {@code NONE} / {@code EXTERNAL} / 其他 {@code APP_PAGE}（HOME / PUBLISH / KNOW_YOUR_PET / HEALTH）/ 未知类型 → false。</li>
 *   <li>{@code toko_banner}：一律 true（{@code shop_banners} 只出现在 Toko 页）。</li>
 *   <li>{@code newbie_coupon}：一律 true（Toko 新人券；不查配置）。</li>
 *   <li>配置里查不到 token（已删除）→ {@code null}；未知 {@code placement_type} → {@code null}。</li>
 * </ul>
 * 运营事后改跳转会改变回补窗口内的历史行（已知偏差，写进口径条目）。
 */
public final class PlacementTokoRule {

    public static final String OPS_POPUP = "ops_popup";
    public static final String TOKO_BANNER = "toko_banner";
    public static final String NEWBIE_COUPON = "newbie_coupon";

    /** 业务库运营位配置里的跳转目标（{@code shop_banners} 只取 {@code target_type}，{@code targetValue} 为空）。 */
    public record Target(String targetType, String targetValue) {
    }

    private PlacementTokoRule() {
    }

    /**
     * 运营位是否推广 Toko。
     *
     * @param placementType 事件属性 {@code placement_type}
     * @param target        业务库配置；{@code null} = 查不到（已删除）
     * @return true / false；{@code null} = 无法判定（配置已删、未知类型）
     */
    public static Boolean promotesToko(String placementType, Target target) {
        if (NEWBIE_COUPON.equals(placementType)) {
            return Boolean.TRUE;
        }
        if (TOKO_BANNER.equals(placementType)) {
            return target == null ? null : Boolean.TRUE;
        }
        if (OPS_POPUP.equals(placementType)) {
            return target == null ? null : opsTarget(target.targetType(), target.targetValue());
        }
        return null;
    }

    /** 运营弹窗 / 运营推送的跳转目标是否指向 Toko（{@code OpsTargetPolicy} 同一套取值）。 */
    public static boolean opsTarget(String targetType, String targetValue) {
        if (targetType == null) {
            return false;
        }
        return switch (targetType) {
            case "PRODUCT", "CATEGORY" -> true;
            case "APP_PAGE" -> "TOKO".equals(targetValue);
            default -> false;
        };
    }
}
