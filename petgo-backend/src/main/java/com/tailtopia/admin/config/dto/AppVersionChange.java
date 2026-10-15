package com.tailtopia.admin.config.dto;

import java.util.List;

/**
 * App 版本配置保存前的一次预检结果（V1.3.4 后台返工清单 §2 · UI 稿 G3 / G4）：逐字段改动（值已 trim）。
 *
 * @param field 表单参数名（iosLatestVersion / androidLatestVersion / minSupportedVersion / iosStoreUrl / androidStoreUrl）
 */
public record AppVersionChange(String field, String oldValue, String newValue) {

    public static final String IOS_LATEST = "iosLatestVersion";
    public static final String ANDROID_LATEST = "androidLatestVersion";
    public static final String MIN_SUPPORTED = "minSupportedVersion";
    public static final String IOS_STORE_URL = "iosStoreUrl";
    public static final String ANDROID_STORE_URL = "androidStoreUrl";

    /** 改动里是否含最低支持版本（含则走 G4 输入确认，否则 G3）。 */
    public static boolean touchesMin(List<AppVersionChange> changes) {
        return changes.stream().anyMatch(c -> MIN_SUPPORTED.equals(c.field()));
    }
}
