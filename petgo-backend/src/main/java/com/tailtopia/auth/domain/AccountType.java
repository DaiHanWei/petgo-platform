package com.tailtopia.auth.domain;

/**
 * 账号类型（Story 9.8，A-6）。{@code REAL} 真实用户（Google/Apple 登录）；{@code VIRTUAL} 虚拟账号
 * （运营建，无 google 身份/无密码/无登录，复用 content_posts.author_id 发种子）。
 */
public enum AccountType {
    REAL,
    VIRTUAL
}
