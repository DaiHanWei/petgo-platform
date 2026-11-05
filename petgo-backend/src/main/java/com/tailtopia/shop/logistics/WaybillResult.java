package com.tailtopia.shop.logistics;

/**
 * 下运单结果（v1.4.2 shop Story 5.1 AC5.1）：失败<b>不抛异常</b>，后台据此提示运营（重下 / 取消 / 手工运单）。
 *
 * @param created    是否已建 Biteship 运单
 * @param failReason {@code <类别>:<Biteship 错误码或简述>}；成功为 null。🔒 不含地址 / 电话 / 姓名
 */
public record WaybillResult(boolean created, String failReason) {

    public static WaybillResult ok() {
        return new WaybillResult(true, null);
    }

    public static WaybillResult failed(String reason) {
        return new WaybillResult(false, reason);
    }
}
