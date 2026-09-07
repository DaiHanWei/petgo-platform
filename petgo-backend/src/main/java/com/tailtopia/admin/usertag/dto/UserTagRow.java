package com.tailtopia.admin.usertag.dto;

import java.time.Instant;

/**
 * 用户标签列表的一行（Story 11.3 · AB-12A）。
 *
 * @param badgeColor 徽章圆底（2026-08-28）。列表里要把**真实底色**画出来 ——
 *                   图标是纯白剪影，脱开底色单看那张图什么都看不出来。
 */
public record UserTagRow(long id, String code, String name, String icon, String description,
        com.tailtopia.auth.domain.UserTagBadgeColor badgeColor,
        Instant retiredAt, long activeAssignments) {

    /** 供模板直接写进 style 的色值。 */
    public String badgeHex() {
        return (badgeColor == null ? com.tailtopia.auth.domain.UserTagBadgeColor.GOLD : badgeColor)
                .hex();
    }

    public boolean retired() {
        return retiredAt != null;
    }
}
