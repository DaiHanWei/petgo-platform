package com.tailtopia.auth.domain;

/**
 * 用户角色（落库 varchar + UPPER）。本 Story 仅签发 {@link #USER}；
 * VET@5.1 / ADMIN@3.1 由后续 Epic 引入（决策 C2），此处预留枚举值以容纳门控映射。
 */
public enum Role {
    USER,
    VET,
    ADMIN
}
