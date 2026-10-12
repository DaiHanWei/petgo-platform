package com.tailtopia.ops.target;

/**
 * 下发给 App 的跳转目标 {@code {"type": "...", "value": "..."}}。
 *
 * <p>{@code NONE} 时 {@code value} 为 null，经全局 NON_NULL 整个省略 → {@code {"type":"NONE"}}。
 */
public record OpsTargetView(String type, String value) {

    public static OpsTargetView of(OpsTargetType type, String value) {
        return new OpsTargetView(type.name(), type == OpsTargetType.NONE ? null : value);
    }
}
