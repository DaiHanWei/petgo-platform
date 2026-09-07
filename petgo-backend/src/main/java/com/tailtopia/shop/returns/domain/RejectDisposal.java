package com.tailtopia.shop.returns.domain;

/**
 * 质检不通过后实物的处置方式（Story 5.4，S-10）。
 *
 * <p>🔴 <b>必须二选一，不留悬空</b> —— 用户的货已经寄出来了，
 * 「驳回」之后货在哪、要不要寄回去，是一定要有答案的。
 */
public enum RejectDisposal {
    /** 退回用户。🔴 <b>回寄运费由平台承担</b> —— 是平台判定驳回，不应再让用户付。 */
    RETURN_TO_USER,
    /** 报损。 */
    WRITE_OFF
}
