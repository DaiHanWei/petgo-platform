package com.tailtopia.avatarmoderation.domain;

/**
 * 运营对头像审核队列项的处置结论（内容审核 story 5，§5.3）。
 * {@code PASS} → 记录终态化、头像保留（不推送）；{@code VIOLATION} → 重置为平台默认头像 + 推送。
 */
public enum AvatarDecision {
    PASS,
    VIOLATION
}
