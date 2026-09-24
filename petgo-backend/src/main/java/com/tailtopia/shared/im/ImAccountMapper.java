package com.tailtopia.shared.im;

import java.util.OptionalLong;
import java.util.regex.Pattern;

/**
 * 平台 id ↔ 腾讯 IM userId 映射（Story 5.5）。规则：用户 {@code <prefix>u_<userId>}、兽医 {@code <prefix>v_<vetId>}。
 * 集中映射，禁止散落字符串。
 *
 * <h2>环境前缀（2026-09-24，bug 519/521）</h2>
 * staging 与生产共用同一个腾讯 IM SDKAppID，而 stag 库克隆自生产、新注册 id 与生产真实用户撞号 →
 * 同一 IM 账号被两个环境共用（互踢下线、离线推送投到对方设备）。前缀由 {@code petgo.im.account-prefix}
 * （env {@code IM_ACCOUNT_PREFIX}）控制：<b>生产留空 → 输出与改前逐字一致</b>；stag 设 {@code stg_}。
 *
 * <p>前缀在 {@link ImConfig} 装配 IM 客户端时经 {@link #configure} 注入一次（非法即启动失败）。
 * 反向解析只认<b>带当前前缀</b>的账号：不带当前前缀的（如 stag 上出现生产格式 {@code u_75}）视为
 * 「非本环境账号」，返回 empty，调用方必须忽略/拒绝，<b>绝不</b>误映射到本环境同 id 用户。
 */
public final class ImAccountMapper {

    /** 腾讯 IM identifier 限制内的保守字符集；长度 0~8（留足 userId 位数）。 */
    private static final Pattern PREFIX_PATTERN = Pattern.compile("[a-z0-9_]{0,8}");

    private static final String USER_TAG = "u_";
    private static final String VET_TAG = "v_";

    private static volatile String prefix = "";

    private ImAccountMapper() {
    }

    /**
     * 设置环境前缀（启动期由 {@link ImConfig} 调一次）。{@code null} 视为空串。
     *
     * @throws IllegalStateException 前缀不满足 {@code [a-z0-9_]{0,8}}（fail-fast，配置错误启动即失败）
     */
    public static void configure(String accountPrefix) {
        String p = accountPrefix == null ? "" : accountPrefix;
        if (!PREFIX_PATTERN.matcher(p).matches()) {
            throw new IllegalStateException("IM 账号前缀配置非法（petgo.im.account-prefix / IM_ACCOUNT_PREFIX）："
                    + "只允许 [a-z0-9_]，长度 0~8，当前值=\"" + p + "\"");
        }
        prefix = p;
    }

    /** 当前环境前缀（空串=生产/未设）。 */
    public static String prefix() {
        return prefix;
    }

    public static String userImId(long userId) {
        return prefix + USER_TAG + userId;
    }

    public static String vetImId(long vetId) {
        return prefix + VET_TAG + vetId;
    }

    /** IM 账号 → 用户 id；非本环境账号 / 非用户账号 / 格式非法 → empty。 */
    public static OptionalLong parseUserId(String imId) {
        return parse(imId, USER_TAG);
    }

    /** IM 账号 → 兽医 id；非本环境账号 / 非兽医账号 / 格式非法 → empty。 */
    public static OptionalLong parseVetId(String imId) {
        return parse(imId, VET_TAG);
    }

    /** 是否本环境的用户或兽医 IM 账号（带当前前缀且格式合法）。 */
    public static boolean isLocalAccount(String imId) {
        return parseUserId(imId).isPresent() || parseVetId(imId).isPresent();
    }

    private static OptionalLong parse(String imId, String tag) {
        String head = prefix + tag;
        if (imId == null || !imId.startsWith(head)) {
            return OptionalLong.empty();
        }
        String digits = imId.substring(head.length());
        if (digits.isEmpty() || digits.length() > 18) {
            return OptionalLong.empty();
        }
        for (int i = 0; i < digits.length(); i++) {
            char c = digits.charAt(i);
            if (c < '0' || c > '9') {
                return OptionalLong.empty();
            }
        }
        return OptionalLong.of(Long.parseLong(digits));
    }
}
