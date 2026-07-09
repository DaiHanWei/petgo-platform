package com.tailtopia.namemoderation.domain;

/**
 * 运营对名称审核队列项的处置结论（内容审核 story 4，§5.8）。
 * {@code PASS} → 记录终态化、名称保留；{@code VIOLATION} → 重置为系统默认编码名 + 推送。
 */
public enum NameDecision {
    PASS,
    VIOLATION
}
