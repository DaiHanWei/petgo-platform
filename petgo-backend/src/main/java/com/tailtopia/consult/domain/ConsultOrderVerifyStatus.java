package com.tailtopia.consult.domain;

/**
 * 兽医咨询订单运营待核查标记（Story 9.3，AB-8B）。NULL=未标记；纯人工注记，不改订单业务状态、不触发退款。
 */
public enum ConsultOrderVerifyStatus {
    TO_VERIFY,
    VERIFIED
}
