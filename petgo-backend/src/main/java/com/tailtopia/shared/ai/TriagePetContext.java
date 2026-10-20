package com.tailtopia.shared.ai;

import java.math.BigDecimal;

/**
 * AI 分诊的宠物只读上下文（V1.4.0 multi-pet Story 4.1 · P-1 / AD-13）：由所选宠物生成，只在内存中组装传给
 * {@link GeminiClient}，<b>不入库、不落日志</b>（体重 / 年龄属宠物资料）。
 *
 * <p>🔴 只放<b>结构化字段</b>，<b>不含</b>宠物名 / 品种 / 简介等自由文本：既不把档案里的 PII 发给第三方，
 * 也杜绝经档案字段向模型注入指令。拼进 prompt 时只拼进<b>用户侧内容</b>（{@code contents}），
 * 绝不进 {@code systemInstruction}；{@code SafetyRuleLayer} 不以它为文本信号源。
 *
 * @param species   物种枚举名（{@code CAT} / {@code DOG} / {@code OTHER}），可空；未知值拼 prompt 时整行省略
 * @param ageMonths 按生日换算的 UTC 整月龄（未来生日钳 0），生日缺失为 null
 * @param weightKg  体重（kg），可空
 */
public record TriagePetContext(String species, Integer ageMonths, BigDecimal weightKg) {

    /**
     * code review 4-1 B2：不打印取值（体重 / 年龄属宠物资料，record 默认 toString 会把它们带进日志 / 异常信息）。
     */
    @Override
    public String toString() {
        return "TriagePetContext[redacted]";
    }

    /** 三字段全空（等同不带上下文）。 */
    public boolean isEmpty() {
        return species == null && ageMonths == null && weightKg == null;
    }
}
