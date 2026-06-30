package com.tailtopia.admin.usermgmt.domain;

/**
 * 用户删除类型（Story 3.3，AB-UA-03）。承载于审计日志（不入 account_deletions 表，保 7.3 表冻结 + PII-free）。
 *
 * <ul>
 *   <li>{@link #USER_REQUEST} D1 用户申请注销：内容匿名化保留 + 档案/名片删 + 问诊匿名（既有 7.3 编排原样）。</li>
 *   <li>{@link #VIOLATION} D2 违规处置：在 D1 之上前置「下架该用户全部内容」。</li>
 * </ul>
 */
public enum DeletionType {
    USER_REQUEST,
    VIOLATION;

    public static DeletionType fromOrNull(String v) {
        if (v == null) {
            return null;
        }
        try {
            return valueOf(v);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
