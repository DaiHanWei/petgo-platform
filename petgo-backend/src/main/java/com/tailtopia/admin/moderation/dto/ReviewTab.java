package com.tailtopia.admin.moderation.dto;

import java.util.Set;

/**
 * 统一复核工作台页签（V1.3.0 Story 2.4 AC1）。四个页签 = 内容送审 / 内容举报 / 名称审核 / 头像审核；
 * 后两者都落在 {@link TicketType#ACCOUNT_IDENTITY} 上，靠 {@code sub_type} 切分。
 * 页签结构可扩展：新 {@link TicketType}（如 Story 5.4 的场所举报）加一行即出现新页签（AC8）。
 */
public enum ReviewTab {

    SUBMISSION("submission", TicketType.CONTENT_SUBMISSION, Set.of()),
    REPORT("report", TicketType.CONTENT_REPORT, Set.of()),
    NAME("name", TicketType.ACCOUNT_IDENTITY, Set.of("NICKNAME", "PET_NAME")),
    AVATAR("avatar", TicketType.ACCOUNT_IDENTITY, Set.of("USER_AVATAR", "PET_AVATAR"));

    private final String param;
    private final TicketType type;
    private final Set<String> subTypes;

    ReviewTab(String param, TicketType type, Set<String> subTypes) {
        this.param = param;
        this.type = type;
        this.subTypes = subTypes;
    }

    public String param() {
        return param;
    }

    public TicketType type() {
        return type;
    }

    /** 该页签限定的 sub_type 集合（空 = 不限定）。 */
    public Set<String> subTypes() {
        return subTypes;
    }

    /** i18n：admin.v130.review.tab.&lt;param&gt; */
    public String titleKey() {
        return "admin.v130.review.tab." + param;
    }

    /** 名称 / 头像页签才有「用户 / 宠物」二选。 */
    public boolean hasSubTypeFilter() {
        return this == NAME || this == AVATAR;
    }

    /** {@code ?tab=} 解析；非法 / 缺省 → 内容送审（AC1 默认）。 */
    public static ReviewTab fromParam(String raw) {
        if (raw != null) {
            for (ReviewTab t : values()) {
                if (t.param.equalsIgnoreCase(raw.trim()) || t.name().equalsIgnoreCase(raw.trim())) {
                    return t;
                }
            }
        }
        return SUBMISSION;
    }

    /** 旧链接 {@code ?type=CONTENT_REPORT} 兼容映射（AC1）：ACCOUNT_IDENTITY → 名称页签；其余 → 对应页签；未知 → null。 */
    public static ReviewTab fromLegacyType(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            TicketType t = TicketType.valueOf(raw.trim());
            return switch (t) {
                case CONTENT_SUBMISSION -> SUBMISSION;
                case CONTENT_REPORT -> REPORT;
                case ACCOUNT_IDENTITY -> NAME;
                default -> null;
            };
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** 按 sub_type 判定一行属于哪个页签（名称 / 头像同 TicketType）。 */
    public static ReviewTab of(UnifiedTicketRow row) {
        if (row.type() == TicketType.ACCOUNT_IDENTITY) {
            return AVATAR.subTypes.contains(row.subType()) ? AVATAR : NAME;
        }
        return row.type() == TicketType.CONTENT_REPORT ? REPORT : SUBMISSION;
    }
}
