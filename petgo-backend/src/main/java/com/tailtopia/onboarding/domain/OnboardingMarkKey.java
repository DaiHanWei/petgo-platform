package com.tailtopia.onboarding.domain;

import java.util.Arrays;
import java.util.Optional;

/**
 * 一次性引导标记的**键登记表**（V1.3.0 批次 A · Story 5.4 · AD-A21.3）。
 *
 * <h2>🔴 一个引导一个键，禁止共用</h2>
 * 本批次**只落一个键**：{@link #KTP_MOVED}（「身份证挪进 Know Your Pet 了」）。
 * 批次 C 的 Tailsonality 入口引导**必须另起一个键** —— PRD 明确那是**两次独立触发**，
 * 共用一个键会让看过第一次的人再也收不到第二次。
 *
 * <h2>为什么是枚举而不是随便一个字符串</h2>
 * 端点收的是客户端传来的键。开放任意字符串等于让客户端往表里写它想写的任何东西：
 * 既可能撑爆行数，也会让「这个键到底是谁在用」变成考古题。
 * 这里**只认登记过的键**，没登记的直接拒。
 */
public enum OnboardingMarkKey {

    /** 「宠物身份证已移入 Know Your Pet 聚合页」的迁移引导（Story 5.4）。 */
    KTP_MOVED("ktp_moved");

    OnboardingMarkKey(String wire) {
        this.wire = wire;
    }

    /** 落库与线格式用的键名。 */
    private final String wire;

    public String wire() {
        return wire;
    }

    /** 线格式 → 枚举；未登记的键返回空（调用方按 422 处理，不写库）。 */
    public static Optional<OnboardingMarkKey> fromWire(String wire) {
        return Arrays.stream(values()).filter(k -> k.wire.equals(wire)).findFirst();
    }
}
