package com.petgo.auth.domain;

/**
 * refresh 令牌主体类型（Story 5.1）。{@code refresh_tokens.user_id} 的命名空间归属：
 * 用户表 {@code users} 与兽医表 {@code vet_accounts} 各自独立自增，必须用本字段区分，
 * 防兽医的 refresh 在 {@code /auth/refresh} 被误当作同 id 的用户续签 token。
 */
public enum SubjectType {
    USER,
    VET
}
