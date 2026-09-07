package com.tailtopia.shop.address.domain;

import com.tailtopia.shared.error.AppException;
import java.util.regex.Pattern;

/**
 * 印尼手机号归一化与校验（Story 2.1，决策 <b>C-15</b>，原 SPEC-13）。
 *
 * <p><b>存储口径：E.164</b> —— {@code +62} + 9~12 位有效位，<b>首位必为 8</b>。
 * 用户可能以三种形式输入，一律归一到同一个存储值：
 * <pre>
 *   08123456789        ─┐
 *   8123456789         ─┼─→  +628123456789
 *   +62 812-3456-789   ─┘
 * </pre>
 *
 * <p>🔴 <b>下限是 9 位，不是 PRD 原文的 8 位。</b>8 位会放进大量无效号码，
 * 而<b>无效号 = 快递员联系不上 = 真实履约失败</b> —— 这个错的代价由平台承担（自营），
 * 且发生在最贵的环节（货已发出）。放宽这一位省下的注册摩擦远抵不上一单履约失败。
 *
 * <p>🔴 <b>自动剥前导 0</b>：印尼本地写法 {@code 08xx} 里的 0 是国内长途前缀，
 * 与国际区号 {@code +62} 互斥。不剥会存成 {@code +6208...}，快递系统拨不通。
 *
 * <p>🔒 本类<b>绝不把号码写进日志或异常 detail</b>（NFR-5）——
 * 报错只说"格式不对"，不回显用户输入的号码。
 */
public final class IndonesiaPhone {

    /** 归一化后的有效位：首位 8，总长 9~12。 */
    private static final Pattern NORMALIZED = Pattern.compile("^8[0-9]{8,11}$");

    private static final String CC = "+62";

    private IndonesiaPhone() {
    }

    /**
     * 归一化到 E.164。
     *
     * @throws AppException 校验失败。🔒 <b>detail 里不含用户输入</b>。
     */
    public static String normalize(String raw) {
        if (raw == null || raw.isBlank()) {
            throw AppException.validation("请填写收件人手机号");
        }
        // 只保留数字与开头的 +，其余（空格、连字符、括号）全部剔除
        String digits = raw.replaceAll("[^0-9+]", "");

        // 剥国际区号：+62 / 62 开头都按已带区号处理
        if (digits.startsWith("+62")) {
            digits = digits.substring(3);
        } else if (digits.startsWith("62")) {
            digits = digits.substring(2);
        }
        // 剥剩余的 + （形如 +0812 这类畸形输入）
        digits = digits.replace("+", "");

        // 🔴 剥前导 0：国内长途前缀，与 +62 互斥
        while (digits.startsWith("0")) {
            digits = digits.substring(1);
        }

        if (!NORMALIZED.matcher(digits).matches()) {
            throw AppException.validation(
                    "手机号格式不正确：应为印尼手机号，8 开头共 9~12 位（如 08123456789）");
        }
        return CC + digits;
    }

    /** 仅校验不抛错（供批量/试探场景）。 */
    public static boolean isValid(String raw) {
        try {
            normalize(raw);
            return true;
        } catch (AppException e) {
            return false;
        }
    }
}
