package com.tailtopia.shared.logistics.biteship.dto;

/** 取消运单结果（v1.4.2 shop Story 1.1 定形，Story 5.1 真实现）。 */
public sealed interface CancelOutcome {

    /** 已取消。 */
    record Cancelled() implements CancelOutcome {
    }

    /** 运单已进入不可取消状态。 */
    record NotCancellable(String providerStatus) implements CancelOutcome {
    }

    /** 结果不确定。 */
    record Unknown(String cause) implements CancelOutcome {
    }
}
