package com.tailtopia.profile.service;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 客户端版本判定（V1.4.0 上线前旧版兼容检查）：App 自 V1.3.4 起每个请求带 {@code X-App-Version}
 * （{@code PackageInfo.version}，如 {@code 1.4.0}；stag 包带 {@code -stag} 后缀，原样上报）。
 *
 * <p>只取开头三段数字比较；<b>缺头 / 解析不了一律按旧版</b>（≤1.3.3 不带此头）。
 */
public final class ClientAppVersion {

    /** 头名，与 App 端 {@code ClientInfoInterceptor.versionHeader} 一致。 */
    public static final String HEADER = "X-App-Version";

    /** 支持多宠物的最低客户端版本。 */
    private static final int[] MULTI_PET_MIN = {1, 4, 0};

    /**
     * V1.4.2 客户端（新结算 / 物流 / 账号级里程碑）。低于它的旧包由后端做兼容（1.4.2 上线前旧版兼容检查）：
     * 隐藏新里程碑、订单详情 status 与 displayStatus 对齐、包裹只给当前有效运单、放宽 10 分钟出码窗口。
     */
    private static final int[] V142_MIN = {1, 4, 2};

    private static final Pattern LEADING = Pattern.compile("^\\s*(\\d{1,6})\\.(\\d{1,6})\\.(\\d{1,6})");

    private ClientAppVersion() {
    }

    /** 客户端是否支持多宠物（≥ 1.4.0）。 */
    public static boolean supportsMultiPet(String header) {
        return atLeast(header, MULTI_PET_MIN);
    }

    /** 客户端是否为 V1.4.2 及以上；缺头 / 解析不了按旧版（false）。 */
    public static boolean isV142OrLater(String header) {
        return atLeast(header, V142_MIN);
    }

    private static boolean atLeast(String header, int[] min) {
        if (header == null) {
            return false;
        }
        Matcher m = LEADING.matcher(header);
        if (!m.find()) {
            return false;
        }
        for (int i = 0; i < 3; i++) {
            int c = Integer.compare(Integer.parseInt(m.group(i + 1)), min[i]);
            if (c != 0) {
                return c > 0;
            }
        }
        return true;
    }
}
