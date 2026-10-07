package com.tailtopia.tailsonality.domain;

import java.util.List;
import java.util.Map;

/**
 * 计分（V1.3.2 Story 2.1 · 内容设计 §6.7）。纯函数，无 Spring 依赖。
 *
 * <ol>
 *   <li>逐轴求和：轴得分 = 该轴全部题目所选权重之和；轴与轴不交叉。</li>
 *   <li>方向：得分 &gt; 0 取 {@link TailsonalityAxis#positiveLetter()}，&lt; 0 取负分字母 —— 情绪轴正分是 <b>F</b>。</li>
 *   <li>平局（= 0）：取该轴锚题所选权重的正负。</li>
 *   <li>拼接「社交-探索-情绪-驱动」+ 能量后缀。</li>
 * </ol>
 *
 * <p>调用方须先校验 {@code answers} 键集合 = {@link TailsonalityCatalog#QUESTION_IDS} 且值 ∈ 0..3。
 */
public final class TailsonalityScorer {

    private static final List<TailsonalityAxis> LETTER_AXES = List.of(
            TailsonalityAxis.EI, TailsonalityAxis.NS, TailsonalityAxis.TF, TailsonalityAxis.JP);

    private TailsonalityScorer() {
    }

    public static TailsonalityCode score(TailsonalityQuestionSet set, Map<String, Integer> answers) {
        StringBuilder letters = new StringBuilder(4);
        for (TailsonalityAxis axis : LETTER_AXES) {
            letters.append(letterOf(set, axis, answers));
        }
        return new TailsonalityCode(letters.toString(), String.valueOf(letterOf(set, TailsonalityAxis.ENERGY, answers)));
    }

    private static char letterOf(TailsonalityQuestionSet set, TailsonalityAxis axis, Map<String, Integer> answers) {
        int sum = 0;
        for (String q : TailsonalityCatalog.AXIS_QUESTIONS.get(axis)) {
            sum += weightOf(set, q, answers);
        }
        if (sum == 0) {
            sum = weightOf(set, TailsonalityCatalog.ANCHOR.get(axis), answers);
        }
        return sum > 0 ? axis.positiveLetter() : axis.negativeLetter();
    }

    private static int weightOf(TailsonalityQuestionSet set, String questionId, Map<String, Integer> answers) {
        Integer idx = answers.get(questionId);
        if (idx == null) {
            throw new IllegalArgumentException("missing answer");
        }
        return TailsonalityCatalog.weight(set, questionId, idx);
    }
}
