package com.tailtopia.entitlement.service;

import java.time.Instant;

/** 发放结果（架构 §5，冻结）：{@code replayed = true} 表示同键同类型已发过，返回的是原批。 */
public record GrantResult(long creditId, Instant expiresAt, boolean replayed) { }
