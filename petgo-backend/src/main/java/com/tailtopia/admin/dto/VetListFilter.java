package com.tailtopia.admin.dto;

/**
 * 兽医列表筛选条件（Story 2.2）。任一为 null/空即不过滤该维度。
 *
 * @param accountStatus 账号状态 ACTIVE/BANNED（null=全部）
 * @param qualStatus    资质状态 6 态名（null=全部）
 * @param online        在线维度 ONLINE/OFFLINE（ONLINE 含 BUSY；null=全部）
 * @param q             姓名/登录邮箱关键词（大小写不敏感子串；null/空=不搜索）
 */
public record VetListFilter(String accountStatus, String qualStatus, String online, String q) {

    public static VetListFilter none() {
        return new VetListFilter(null, null, null, null);
    }

    private static String norm(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }

    /** 归一化：空串视为 null（不过滤）。 */
    public VetListFilter normalized() {
        return new VetListFilter(norm(accountStatus), norm(qualStatus), norm(online), norm(q));
    }
}
