package com.petgo.triage.domain;

/**
 * 分诊危险级别（Story 4.1）。落库 varchar + UPPER_SNAKE。
 *
 * <p>⚠️ 本枚举值是<b>后置校验的产物</b>，不是模型最终裁决：写库的级别须可被后置安全规则层
 * （Story 4.2）<b>只升不降</b>地覆盖（{@link #atLeast}）。取结果一律读经后置校验后落库的最终值。
 */
public enum DangerLevel {
    GREEN,
    YELLOW,
    RED;

    /** 只升不降：返回两者中更危险（序数更大）的一个。供 4.2 后置层强制升红、绝不降级使用。 */
    public DangerLevel atLeast(DangerLevel other) {
        if (other == null) {
            return this;
        }
        return this.ordinal() >= other.ordinal() ? this : other;
    }

    /** 宽松解析模型字符串；无法识别时<b>保守按需升级方向处理</b>由调用方决定，此处仅做 null。 */
    public static DangerLevel fromNullable(String raw) {
        if (raw == null) {
            return null;
        }
        try {
            return DangerLevel.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
