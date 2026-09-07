package com.tailtopia.admin.contenttag.dto;

import java.time.Instant;

/**
 * 内容标签列表的一行（Story 11.2 · AB-10C）。
 *
 * @param badgeStyle 胶囊底色（2026-08-28）。列表里要把**整枚胶囊**按真实样子画出来 ——
 *                   图标只有 9px、字是白色，脱开胶囊单看那张 PNG 什么都看不出来，
 *                   运营因此判断不了「我配的这枚最后长什么样」。
 */
public record TagRow(long id, String code, String name, String icon, String description,
        com.tailtopia.content.domain.ContentTagBadgeStyle badgeStyle,
        Instant retiredAt, long activeAssignments) {

    /** 供模板直接写进 style 的 CSS 渐变。 */
    public String badgeCss() {
        return (badgeStyle == null
                ? com.tailtopia.content.domain.ContentTagBadgeStyle.SUNSET : badgeStyle).css();
    }

    /** 已下线：不可再分配，但已分配的照旧生效到各自 ends_at。 */
    public boolean retired() {
        return retiredAt != null;
    }
}
