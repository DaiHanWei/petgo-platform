package com.tailtopia.tailsonality.domain;

import static com.tailtopia.tailsonality.domain.TailsonalityQuestionSet.CAT;
import static com.tailtopia.tailsonality.domain.TailsonalityQuestionSet.DOG;
import static com.tailtopia.tailsonality.domain.TailsonalityQuestionSet.GENERAL;

import com.tailtopia.profile.domain.PetType;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Tailsonality 题库常量（V1.3.2 Story 2.1 · 内容设计 §6.1–§6.5）。<b>题号 / 归轴 / 权重的唯一事实源</b>。
 *
 * <p>编译期常量，不建表、不开运营编辑（照 {@code profile.domain.MilestoneCatalog} 的写法）。题干与选项文案在 App。
 *
 * <p>权重按<b>原始选项序号 0..3</b> 存：提交值 0 = 内容设计里该题第 1 个选项。App 若将来打乱显示顺序，
 * 必须在提交前映射回原始序号（AD-2）。
 *
 * <p>⚠️ 三套 54 题的现行权重恰好全是 {@code [+2, +1, -1, -2]}，但<b>仍按「题套 × 题号」逐条列出</b>：
 * 以后某题改权重时必须能只改一套，不得收成一个共享常量。{@code TailsonalityCatalogTest} 逐条钉住。
 */
public final class TailsonalityCatalog {

    private TailsonalityCatalog() {
    }

    /** 有序题号：行为观察题 Q1–Q15 + 图片题 P1–P3，共 18。 */
    public static final List<String> QUESTION_IDS = List.of(
            "Q1", "Q2", "Q3", "Q4", "Q5", "Q6", "Q7", "Q8", "Q9", "Q10",
            "Q11", "Q12", "Q13", "Q14", "Q15", "P1", "P2", "P3");

    /** 题号归轴（§6.1）。计分按题号归轴，与 App 分页无关。 */
    public static final Map<TailsonalityAxis, List<String>> AXIS_QUESTIONS = axisQuestions();

    /** 平局锚题（§6.7 第 3 条）：各轴第 1 题，区分度最高；选项无 0 权重，锚题本身不会为 0。 */
    public static final Map<TailsonalityAxis, String> ANCHOR = anchors();

    /** 权重：题套 → 题号 → 按原始选项序号 0..3 的 4 个权重。 */
    public static final Map<TailsonalityQuestionSet, Map<String, List<Integer>>> WEIGHTS = weights();

    /** 16 个四字母代号（E/I × N/S × T/F × J/P，按「社交-探索-情绪-驱动」拼写）。 */
    public static final List<String> TYPE_CODES = typeCodes();

    /** 物种 → 题套：CAT→CAT、DOG→DOG、OTHER→GENERAL（PetType 建档后不可改）。 */
    public static TailsonalityQuestionSet forPetType(PetType type) {
        return switch (type) {
            case CAT -> CAT;
            case DOG -> DOG;
            case OTHER -> GENERAL;
        };
    }

    /** 某套某题某选项序号的权重；题号或序号非法 → {@link IllegalArgumentException}（调用方须先校验）。 */
    public static int weight(TailsonalityQuestionSet set, String questionId, int optionIndex) {
        List<Integer> w = WEIGHTS.get(set).get(questionId);
        if (w == null || optionIndex < 0 || optionIndex >= w.size()) {
            throw new IllegalArgumentException("unknown question / option");
        }
        return w.get(optionIndex);
    }

    private static Map<TailsonalityAxis, List<String>> axisQuestions() {
        Map<TailsonalityAxis, List<String>> m = new EnumMap<>(TailsonalityAxis.class);
        m.put(TailsonalityAxis.EI, List.of("Q1", "Q2", "Q3", "P1"));
        m.put(TailsonalityAxis.NS, List.of("Q4", "Q5", "Q6", "Q7"));
        m.put(TailsonalityAxis.TF, List.of("Q8", "Q9", "Q10", "P2"));
        m.put(TailsonalityAxis.JP, List.of("Q11", "Q12", "Q13"));
        m.put(TailsonalityAxis.ENERGY, List.of("Q14", "Q15", "P3"));
        return Collections.unmodifiableMap(m);
    }

    private static Map<TailsonalityAxis, String> anchors() {
        Map<TailsonalityAxis, String> m = new EnumMap<>(TailsonalityAxis.class);
        m.put(TailsonalityAxis.EI, "Q1");
        m.put(TailsonalityAxis.NS, "Q4");
        m.put(TailsonalityAxis.TF, "Q8");
        m.put(TailsonalityAxis.JP, "Q11");
        m.put(TailsonalityAxis.ENERGY, "Q14");
        return Collections.unmodifiableMap(m);
    }

    private static Map<TailsonalityQuestionSet, Map<String, List<Integer>>> weights() {
        Map<TailsonalityQuestionSet, Map<String, List<Integer>>> m = new EnumMap<>(TailsonalityQuestionSet.class);
        // ===== 猫套 §6.2（P1–P3 = 图片题 §6.5，三套共用题干，权重逐套列出）=====
        put(m, CAT, "Q1", 2, 1, -1, -2);
        put(m, CAT, "Q2", 2, 1, -1, -2);
        put(m, CAT, "Q3", 2, 1, -1, -2);
        put(m, CAT, "Q4", 2, 1, -1, -2);
        put(m, CAT, "Q5", 2, 1, -1, -2);
        put(m, CAT, "Q6", 2, 1, -1, -2);
        put(m, CAT, "Q7", 2, 1, -1, -2);
        put(m, CAT, "Q8", 2, 1, -1, -2);
        put(m, CAT, "Q9", 2, 1, -1, -2);
        put(m, CAT, "Q10", 2, 1, -1, -2);
        put(m, CAT, "Q11", 2, 1, -1, -2);
        put(m, CAT, "Q12", 2, 1, -1, -2);
        put(m, CAT, "Q13", 2, 1, -1, -2);
        put(m, CAT, "Q14", 2, 1, -1, -2);
        put(m, CAT, "Q15", 2, 1, -1, -2);
        put(m, CAT, "P1", 2, 1, -1, -2);
        put(m, CAT, "P2", 2, 1, -1, -2);
        put(m, CAT, "P3", 2, 1, -1, -2);
        // ===== 狗套 §6.3（P1–P3 = 图片题 §6.5，三套共用题干，权重逐套列出）=====
        put(m, DOG, "Q1", 2, 1, -1, -2);
        put(m, DOG, "Q2", 2, 1, -1, -2);
        put(m, DOG, "Q3", 2, 1, -1, -2);
        put(m, DOG, "Q4", 2, 1, -1, -2);
        put(m, DOG, "Q5", 2, 1, -1, -2);
        put(m, DOG, "Q6", 2, 1, -1, -2);
        put(m, DOG, "Q7", 2, 1, -1, -2);
        put(m, DOG, "Q8", 2, 1, -1, -2);
        put(m, DOG, "Q9", 2, 1, -1, -2);
        put(m, DOG, "Q10", 2, 1, -1, -2);
        put(m, DOG, "Q11", 2, 1, -1, -2);
        put(m, DOG, "Q12", 2, 1, -1, -2);
        put(m, DOG, "Q13", 2, 1, -1, -2);
        put(m, DOG, "Q14", 2, 1, -1, -2);
        put(m, DOG, "Q15", 2, 1, -1, -2);
        put(m, DOG, "P1", 2, 1, -1, -2);
        put(m, DOG, "P2", 2, 1, -1, -2);
        put(m, DOG, "P3", 2, 1, -1, -2);
        // ===== 通用套 §6.4（P1–P3 = 图片题 §6.5，三套共用题干，权重逐套列出）=====
        put(m, GENERAL, "Q1", 2, 1, -1, -2);
        put(m, GENERAL, "Q2", 2, 1, -1, -2);
        put(m, GENERAL, "Q3", 2, 1, -1, -2);
        put(m, GENERAL, "Q4", 2, 1, -1, -2);
        put(m, GENERAL, "Q5", 2, 1, -1, -2);
        put(m, GENERAL, "Q6", 2, 1, -1, -2);
        put(m, GENERAL, "Q7", 2, 1, -1, -2);
        put(m, GENERAL, "Q8", 2, 1, -1, -2);
        put(m, GENERAL, "Q9", 2, 1, -1, -2);
        put(m, GENERAL, "Q10", 2, 1, -1, -2);
        put(m, GENERAL, "Q11", 2, 1, -1, -2);
        put(m, GENERAL, "Q12", 2, 1, -1, -2);
        put(m, GENERAL, "Q13", 2, 1, -1, -2);
        put(m, GENERAL, "Q14", 2, 1, -1, -2);
        put(m, GENERAL, "Q15", 2, 1, -1, -2);
        put(m, GENERAL, "P1", 2, 1, -1, -2);
        put(m, GENERAL, "P2", 2, 1, -1, -2);
        put(m, GENERAL, "P3", 2, 1, -1, -2);
        Map<TailsonalityQuestionSet, Map<String, List<Integer>>> frozen = new EnumMap<>(TailsonalityQuestionSet.class);
        m.forEach((k, v) -> frozen.put(k, Collections.unmodifiableMap(v)));
        return Collections.unmodifiableMap(frozen);
    }

    private static void put(Map<TailsonalityQuestionSet, Map<String, List<Integer>>> m,
            TailsonalityQuestionSet set, String questionId, int w0, int w1, int w2, int w3) {
        m.computeIfAbsent(set, k -> new LinkedHashMap<>()).put(questionId, List.of(w0, w1, w2, w3));
    }

    private static List<String> typeCodes() {
        List<String> out = new ArrayList<>(16);
        for (char a : new char[] {'E', 'I'}) {
            for (char b : new char[] {'N', 'S'}) {
                for (char c : new char[] {'T', 'F'}) {
                    for (char d : new char[] {'J', 'P'}) {
                        out.add("" + a + b + c + d);
                    }
                }
            }
        }
        return List.copyOf(out);
    }
}
