package com.tailtopia.shared.logistics.biteship.dto;

/** 下运单结果（v1.4.2 shop Story 1.1 定形，Story 5.1 真实现）。 */
public sealed interface CreateOutcome {

    /** 已建单。 */
    record Created(BiteshipOrderSnapshot order) implements CreateOutcome {
    }

    /** 4xx 明确拒绝（含 40002060 重复 reference）。 */
    record Rejected(String code, String message) implements CreateOutcome {
    }

    /** 超时 / 5xx / 网络错误——结果不确定。 */
    record Unknown(String cause) implements CreateOutcome {
    }
}
