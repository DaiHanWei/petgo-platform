package com.tailtopia.analytics.config;

import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import org.springframework.boot.convert.DurationStyle;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.util.unit.DataSize;

/**
 * 埋点配置的「按字符串接收、自己解析」工具（analytics-pipeline Story 4.9 AC8–AC12，架构「启动隔离」）。
 *
 * <p>所有方法<b>绝不抛</b>：值非法返回 {@code null}，由调用方决定停用对应功能或回退默认值，并只把<b>键名</b>记进
 * {@code invalidReason}（不记配置值，配置值可能是误填的凭证）。写法沿用 4-2 的 {@code Ga4SnapshotProperties}。
 */
public final class ConfigValues {

    private ConfigValues() {
    }

    private static String norm(String raw) {
        return raw == null ? "" : raw.strip();
    }

    /** {@code true} / {@code false}（不区分大小写）；其他（如 {@code flase}）→ {@code null}。 */
    public static Boolean bool(String raw) {
        String v = norm(raw).toLowerCase(Locale.ROOT);
        if ("true".equals(v)) {
            return Boolean.TRUE;
        }
        if ("false".equals(v)) {
            return Boolean.FALSE;
        }
        return null;
    }

    /** 闭区间 {@code [min, max]} 内的整数；否则 {@code null}。 */
    public static Integer intIn(String raw, int min, int max) {
        try {
            int v = Integer.parseInt(norm(raw));
            return v < min || v > max ? null : v;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 正整数（{@code >= 1}）；否则 {@code null}。 */
    public static Integer positiveInt(String raw) {
        return intIn(raw, 1, Integer.MAX_VALUE);
    }

    /** 正长整数（{@code >= 1}）；否则 {@code null}。 */
    public static Long positiveLong(String raw) {
        try {
            long v = Long.parseLong(norm(raw));
            return v < 1 ? null : v;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 时长：纯数字按 {@code defaultUnit}，也接受 {@code 60s} / {@code 5m} / ISO-8601 {@code PT1M}（Spring Boot 的
     * {@link DurationStyle}，与 {@code @Scheduled(fixedDelayString)} 及 {@code Duration} 属性绑定同口径）。
     *
     * @param allowZero {@code true} = 允许 0（初始延迟）；负数一律非法
     */
    public static Duration duration(String raw, ChronoUnit defaultUnit, boolean allowZero) {
        String v = norm(raw);
        if (v.isEmpty()) {
            return null;
        }
        try {
            Duration d = DurationStyle.detectAndParse(v, defaultUnit);
            if (d.isNegative() || (!allowZero && d.isZero())) {
                return null;
            }
            return d;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** 正的字节量：{@code 40GB}（Spring 的 GB = 1024³）或纯数字（字节）；否则 {@code null}。 */
    public static DataSize positiveDataSize(String raw) {
        String v = norm(raw);
        if (v.isEmpty()) {
            return null;
        }
        try {
            DataSize d = DataSize.parse(v);
            return d.toBytes() <= 0 ? null : d;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** {@code "-"}（{@link Scheduled#CRON_DISABLED}）= 关闭定时任务，是<b>合法</b>值（测试基类靠它关闭各定时任务）。 */
    public static boolean isCronDisabled(String raw) {
        return Scheduled.CRON_DISABLED.equals(norm(raw));
    }

    /** cron 是否可用：{@code "-"} 或 Spring 6 位 cron 合法。 */
    public static boolean isValidCronOrDisabled(String raw) {
        return isCronDisabled(raw) || CronExpression.isValidExpression(norm(raw));
    }

    /** 两段「原因」拼接（只含键名），任一为 null 取另一个。 */
    public static String join(String a, String b) {
        if (a == null) {
            return b;
        }
        return b == null ? a : a + "；" + b;
    }
}
