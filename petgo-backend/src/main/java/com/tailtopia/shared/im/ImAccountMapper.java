package com.tailtopia.shared.im;

/**
 * 平台 id ↔ 腾讯 IM userId 映射（Story 5.5）。规则：用户 {@code u_<userId>}、兽医 {@code v_<vetId>}。
 * 集中映射，禁止散落字符串。
 */
public final class ImAccountMapper {

    private ImAccountMapper() {
    }

    public static String userImId(long userId) {
        return "u_" + userId;
    }

    public static String vetImId(long vetId) {
        return "v_" + vetId;
    }
}
