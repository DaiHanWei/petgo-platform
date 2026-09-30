package com.tailtopia.purchase.domain;

/** {@code KeepsakeGranter.grant} 的结果。 */
public enum GrantOutcome {
    /** 本次发放成功。 */
    GRANTED,
    /** 业务行此前已解锁（本次付款重复）→ 购买行 DUPLICATE_PAID。 */
    ALREADY_UNLOCKED,
    /** 业务行不存在（如已删档）→ 购买行 ORPHAN_PAID。 */
    REF_MISSING
}
