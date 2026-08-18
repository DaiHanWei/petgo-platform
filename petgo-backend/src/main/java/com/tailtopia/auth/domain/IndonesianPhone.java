package com.tailtopia.auth.domain;

import java.util.regex.Pattern;

/**
 * 印尼手机号的**基础格式校验与归一**（V1.1.6 Story 7.1 · FR-70）。
 *
 * <h2>🔴 宁松勿紧，这是 AC 明写的</h2>
 * 印尼手机号写法很杂：{@code 0812-3456-7890}、{@code +62 812 3456 7890}、{@code 62812345678}……
 * AC 原话是「常见合法写法均应通过 —— <b>校验过严会挡住真实用户</b>」，
 * 并专门配了一个**保存失败率**埋点来发现"是不是我们卡太严"。
 *
 * <p>所以这里**刻意不去精确匹配运营商号段**（号段会变，卡死等于给自己埋雷），
 * 只认三件事：是移动号（国内部分以 8 开头）、位数在一个宽区间内、没有非法字符。
 *
 * <h2>归一</h2>
 * 不同写法归一成 {@code +62...} 的统一形态再入库 —— 否则同一个人可能以三种样子
 * 出现在运营的导出表里。
 * ⚠️ 代价：用户输入 {@code 0812-3456-7890}、再打开编辑框看到的是归一后的形态。
 * 这是可接受的常见做法。
 *
 * <h2>不做的事</h2>
 * **不发验证码、不做真实性验证、不用于登录**（FR-70 明确排除）。这只是一个联系方式。
 */
public final class IndonesianPhone {

    /** 国内部分：以 8 开头，其后 7~12 位。区间刻意放宽（见类注释）。 */
    private static final Pattern NATIONAL = Pattern.compile("^8\\d{7,12}$");

    /** 允许出现在输入里的分隔符（空格、连字符、括号、点）。 */
    private static final Pattern SEPARATORS = Pattern.compile("[\\s\\-().]");

    private IndonesianPhone() {
    }

    /**
     * 归一到 {@code +62...}；格式不合法返回 {@code null}（调用方据此给"格式不对"的提示）。
     *
     * <p>空 / 全空白输入返回 {@code null} —— 但那属于**清空**语义，由调用方在进来之前分流，
     * 不要靠本方法的返回值区分"清空"与"格式错"。
     */
    public static String normalizeOrNull(String raw) {
        if (raw == null) {
            return null;
        }
        String cleaned = SEPARATORS.matcher(raw).replaceAll("");
        if (cleaned.isEmpty()) {
            return null;
        }
        // 前缀三种写法归一到国内部分：+62… / 62… / 0…
        String national;
        if (cleaned.startsWith("+62")) {
            national = cleaned.substring(3);
        } else if (cleaned.startsWith("62")) {
            national = cleaned.substring(2);
        } else if (cleaned.startsWith("0")) {
            national = cleaned.substring(1);
        } else {
            national = cleaned;
        }
        if (!NATIONAL.matcher(national).matches()) {
            return null;
        }
        return "+62" + national;
    }
}
