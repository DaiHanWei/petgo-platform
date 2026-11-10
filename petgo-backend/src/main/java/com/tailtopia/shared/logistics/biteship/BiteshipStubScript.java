package com.tailtopia.shared.logistics.biteship;

/**
 * Biteship 桩的测试钩子（v1.4.2 shop Story 5.1 AC3.2）：L1 集成测试与 stag 验收用来推进运单状态 / 制造失败。
 *
 * <p>🔴 <b>仅 {@code BITESHIP_MODE=stub} 时存在</b>（{@link BiteshipConfig#biteshipStubScript}）；live / sandbox 下没有本 bean。
 * 调用方一律经 {@code ObjectProvider#getIfAvailable} 取，取不到即「非桩模式」。
 */
public final class BiteshipStubScript {

    private final StubBiteshipClient stub;

    public BiteshipStubScript(StubBiteshipClient stub) {
        this.stub = stub;
    }

    /** 把桩运单推进到指定 Biteship 状态（{@code updatedAt = now}）。未知运单抛 {@link IllegalArgumentException}。 */
    public void setStatus(String biteshipOrderId, String status) {
        stub.scriptSetStatus(biteshipOrderId, status);
    }

    /** 下一次 {@code createOrder} 返回 {@code Rejected(code)}（code 为空时用 {@code 40000001}）。 */
    public void failNextCreate(String code) {
        stub.scriptFailNextCreate(code);
    }

    /** 下一次 {@code createOrder} 返回 {@code Unknown}（模拟超时，结果不确定）。 */
    public void timeoutNextCreate() {
        stub.scriptTimeoutNextCreate();
    }

    /** 下一次 {@code getOrder} 抛 {@link BiteshipException}（NETWORK）。 */
    public void failNextGetOrder() {
        stub.scriptFailNextGetOrder();
    }
}
