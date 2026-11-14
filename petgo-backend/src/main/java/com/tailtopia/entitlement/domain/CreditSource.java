package com.tailtopia.entitlement.domain;

/** 权益来源（架构 §5）：礼包 / 运营活动 / 后台补发。wire 值即 {@code entitlement_credits.source_type}。 */
public enum CreditSource { PACK, ACTIVITY, ADMIN }
