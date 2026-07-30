/**
 * TailTopia shared — pay（跨模块支付网关基础设施，Story 1.1 / V1.1 资金地基）。
 *
 * <p>抽象 {@link com.tailtopia.shared.pay.PaymentGateway}（Midtrans 收款）+ 按 {@code petgo.pay.mode}
 * 选桩/实（{@link com.tailtopia.shared.pay.PayConfig}）。凭证全部 env 注入、绝不入库/落日志；
 * 异常只记类名（{@link com.tailtopia.shared.pay.PayException}）。业务侧（意图状态机 / 回调去重）在
 * {@code com.tailtopia.pay} 模块，本包只做「与网关对话」的纯基础设施，不依赖业务领域类型。
 */
package com.tailtopia.shared.pay;
