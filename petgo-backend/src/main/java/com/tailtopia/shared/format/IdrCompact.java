package com.tailtopia.shared.format;

/**
 * 卢比金额缩写（V1.4.0 gift Story 2.1 · AC7 · PRD §3.2 影响面）：H5 名片票根「Hadiah」列用。
 *
 * <p>规则（<b>与语言无关</b> —— 卢比金额一律逗号小数、{@code rb} / {@code jt} 后缀，与 App {@code formatIdrAmount}
 * 不随语言变化同口径）：
 * <ul>
 *   <li>{@code amount < 1000}（负数按 0）→ {@code Rp{amount}}，无单位；</li>
 *   <li>{@code 1000 ≤ amount < 1_000_000} → 以 1000 为单位，<b>向下截断</b>到 1 位小数，小数为 0 不写
 *       （{@code Rp188rb}、{@code Rp1,5rb}）；</li>
 *   <li>{@code amount ≥ 1_000_000} → 以 1_000_000 为单位，同上（{@code Rp1,2jt}、{@code Rp1jt}）。</li>
 * </ul>
 * 纯整数运算（{@code amount * 10 / unit}），不用 {@code double}。
 *
 * <p>🔴 App 端 {@code petgo_app/lib/shared/format/idr_compact.dart}（Story 2.2）是同规则的另一份实现，
 * 两边测试逐行照抄同一张用例表（本 story AC7.3）——改一边必须改另一边。
 */
public final class IdrCompact {

    private static final long THOUSAND = 1_000L;
    private static final long MILLION = 1_000_000L;

    private IdrCompact() {
    }

    /**
     * 拆开的数字与单位（供模板给「Rp」与单位单独设字号）。
     *
     * @param number 数字部分（如 {@code "188"}、{@code "1,5"}）
     * @param unit   单位（{@code "rb"} / {@code "jt"}；不足 1000 时为空串）
     */
    public record Parts(String number, String unit) {
    }

    /** 整串，如 {@code "Rp1,5rb"}。 */
    public static String format(long amount) {
        Parts p = parts(amount);
        return "Rp" + p.number() + p.unit();
    }

    /** 拆分为数字与单位：188000 → ({@code "188"}, {@code "rb"})；0 → ({@code "0"}, {@code ""})。 */
    public static Parts parts(long amount) {
        long a = Math.max(0L, amount);
        if (a < THOUSAND) {
            return new Parts(Long.toString(a), "");
        }
        long unit = a < MILLION ? THOUSAND : MILLION;
        String suffix = a < MILLION ? "rb" : "jt";
        // 向下截断到 1 位小数：先放大 10 倍再整除（a ≤ Long.MAX_VALUE / 10 的范围内不溢出，金额远小于此）。
        long tenths = a / unit * 10 + (a % unit) * 10 / unit;
        long whole = tenths / 10;
        long frac = tenths % 10;
        String number = frac == 0 ? Long.toString(whole) : whole + "," + frac;
        return new Parts(number, suffix);
    }
}
