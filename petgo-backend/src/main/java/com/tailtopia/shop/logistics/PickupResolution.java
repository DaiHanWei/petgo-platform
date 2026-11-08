package com.tailtopia.shop.logistics;

/**
 * 揽收失败处置结果（v1.4.2 shop Story 5.2 AC7.1）：失败<b>不抛异常</b>（前置不符仍 409），后台据此提示运营。
 *
 * @param outcome {@link Outcome}
 * @param reason  {@code ABORTED}：Biteship 取消旧运单失败的原因（订单与标记都未动）；{@code WAYBILL_FAILED}：5.1 失败原因码
 *                （标记保留）；{@code RESOLVED} 为 null。🔒 不含收件人信息
 */
public record PickupResolution(Outcome outcome, String reason) {

    public enum Outcome {
        /** 处置完成。 */
        RESOLVED,
        /** 旧运单取消失败（不可取消 / 结果不确定），整个处置中止。 */
        ABORTED,
        /** 旧运单已取消，但重下 Biteship 运单失败（标记保留，可再处置）。 */
        WAYBILL_FAILED
    }

    public static PickupResolution resolved() {
        return new PickupResolution(Outcome.RESOLVED, null);
    }

    public static PickupResolution aborted(String reason) {
        return new PickupResolution(Outcome.ABORTED, reason);
    }

    public static PickupResolution waybillFailed(String reason) {
        return new PickupResolution(Outcome.WAYBILL_FAILED, reason);
    }

    public boolean resolvedOk() {
        return outcome == Outcome.RESOLVED;
    }
}
