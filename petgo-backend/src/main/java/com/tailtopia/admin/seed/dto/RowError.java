package com.tailtopia.admin.seed.dto;

import java.util.Arrays;

/**
 * 一条批量内容行的错误（bug 20260924-565）：存<b>文案码 + 实参</b>，不存成句的中文。
 *
 * <p>此前校验 / 录入 / 发布失败都把中文句子直接写进 {@code seed_batch_rows.error_message}，
 * 后台切到英文、印尼语时预览与工作台照样显示中文。现在由展示端
 * （{@code @seedErr}，见 {@code SeedRowErrorRenderer}）按当前后台语言渲染。
 *
 * @param key  文案码（{@code admin.err.seedBatch.*}）；{@code null} 表示历史遗留的<b>原文</b>，原文在 {@code args[0]}
 * @param args MessageFormat 实参
 */
public record RowError(String key, Object... args) {

    public RowError {
        args = args == null ? new Object[0] : args;
    }

    /** 历史数据 / 未挂码异常的原文：原样显示，不翻译。 */
    public static RowError raw(String text) {
        return new RowError(null, text == null ? "" : text);
    }

    public boolean isRaw() {
        return key == null;
    }

    /** 原文（仅 {@link #isRaw()} 时有意义）。 */
    public String rawText() {
        return args.length == 0 || args[0] == null ? "" : String.valueOf(args[0]);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof RowError other && java.util.Objects.equals(key, other.key)
                && Arrays.equals(args, other.args);
    }

    @Override
    public int hashCode() {
        return 31 * java.util.Objects.hashCode(key) + Arrays.hashCode(args);
    }

    @Override
    public String toString() {
        return isRaw() ? rawText() : key + Arrays.toString(args);
    }
}
