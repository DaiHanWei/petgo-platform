package com.tailtopia.admin.ops;

import com.tailtopia.ops.target.OpsTargetPolicy;
import com.tailtopia.ops.target.OpsTargetType;
import com.tailtopia.shared.error.AppException;
import org.springframework.stereotype.Component;

/**
 * 后台运营位「跳转目标」校验（V1.3.4 AB-30 运营弹窗 / AB-31 运营通知共用）。
 *
 * <p>🔴 <b>规则只问 {@link OpsTargetPolicy}</b>（先 {@code isWellFormed} 再 {@code isAvailable}），本类只负责
 * 把布尔结果翻成<b>按类型区分的错误码</b>：「跳转目标不合法」五种类型共用一句，运营不知道该改哪。
 * App 候选过滤 / 通知发送核心用的是同一个 Policy，两份规则迟早漂移，表现为「后台保存成功、App 永远不跳」。
 *
 * <p>错误码沿用 AB-30 落地时的 {@code admin.err.opsPopup.target*}（两页文案一致，不另立一套同义码）。
 * 发送核心 {@code OpsNotificationCampaignService} 的无码兜底保留不动 —— 后台先在这里拦下并挂码。
 */
@Component
public class AdminOpsTargetValidator {

    public static final String ERR_TYPE = "admin.err.opsPopup.targetType";
    public static final String ERR_NONE_WITH_VALUE = "admin.err.opsPopup.targetNoneWithValue";
    public static final String ERR_PRODUCT = "admin.err.opsPopup.targetProduct";
    public static final String ERR_CATEGORY = "admin.err.opsPopup.targetCategory";
    public static final String ERR_APP_PAGE = "admin.err.opsPopup.targetAppPage";
    public static final String ERR_EXTERNAL = "admin.err.opsPopup.targetExternal";
    public static final String ERR_PRODUCT_UNAVAILABLE = "admin.err.opsPopup.productUnavailable";

    private final OpsTargetPolicy targets;

    public AdminOpsTargetValidator(OpsTargetPolicy targets) {
        this.targets = targets;
    }

    /** 跳转类型：空 → {@code NONE}；非五值之一 → 422 {@link #ERR_TYPE}。 */
    public static OpsTargetType parseType(String raw) {
        if (raw == null || raw.isBlank()) {
            return OpsTargetType.NONE;
        }
        try {
            return OpsTargetType.valueOf(raw.trim());
        } catch (IllegalArgumentException e) {
            throw AppException.validation("取值不合法").code(ERR_TYPE);
        }
    }

    /**
     * 先格式、再可用性（商品须存在且在售）。
     *
     * @param checkAvailability 是否查可用性（AB-30 编辑且目标未改动时传 false：商品临时下架不应挡住改文案）
     */
    public void check(OpsTargetType type, String value, boolean checkAvailability) {
        if (!OpsTargetPolicy.isWellFormed(type, value)) {
            switch (type) {
                case NONE -> throw AppException.validation("「不跳转」不应带跳转值").code(ERR_NONE_WITH_VALUE);
                case PRODUCT -> throw AppException.validation("请填写正确的商品 token").code(ERR_PRODUCT);
                case CATEGORY -> throw AppException.validation("请选择商品分类").code(ERR_CATEGORY);
                case APP_PAGE -> throw AppException.validation("请选择 App 页面").code(ERR_APP_PAGE);
                default -> throw AppException.validation("外部链接须以 http:// 或 https:// 开头").code(ERR_EXTERNAL);
            }
        }
        if (checkAvailability && !targets.isAvailable(type, value)) {
            // 能走到这里的只有 PRODUCT：格式对，但商品不存在或已下架
            throw AppException.validation("商品不存在或已下架").code(ERR_PRODUCT_UNAVAILABLE);
        }
    }
}
