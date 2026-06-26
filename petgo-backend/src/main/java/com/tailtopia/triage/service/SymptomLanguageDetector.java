package com.tailtopia.triage.service;

import java.util.Locale;
import java.util.Set;

/**
 * 症状文本语言检测（确定性，仅区分印尼语 / 英语）。
 *
 * <p>用途：满足「AI 作答语言优先跟随用户文字」的需求，且<b>不依赖模型自判</b>
 * （实测 gemini-2.5-flash 关闭 thinking 后，对「输入是印尼语但 locale=en」的场景不会可靠地
 * 按文字语言作答）。本检测器在调用 Gemini 前先确定性判定输入语言，判得出则覆盖 locale。
 *
 * <p>算法：按高频功能词 / 常见宠物症状词的命中计数比较，多者胜；命中相等（含其它语言、过短、
 * 势均力敌）一律返回 {@code null}，交调用方回落到 App locale（再兜底英语）。印尼语 / 英语两套
 * 词表互不重叠，故任一命中即真实信号。
 */
public final class SymptomLanguageDetector {

    private SymptomLanguageDetector() {}

    /** 印尼语高频词（功能词 + 常见宠物/症状词）。刻意只收强区分词，避免与英语重叠。 */
    private static final Set<String> ID = Set.of(
            "saya", "aku", "kucing", "anjing", "dan", "yang", "tidak", "tak", "nggak", "gak",
            "sakit", "harus", "bagaimana", "gimana", "kenapa", "mengapa", "sudah", "belum",
            "dia", "ini", "itu", "dengan", "untuk", "makan", "minum", "muntah", "diare",
            "demam", "lemas", "lemah", "apakah", "bisa", "akan", "dari", "pada", "adalah",
            "ada", "terus", "semangat", "hewan", "dokter", "periksa", "tolong", "banyak",
            "sering", "hari", "badan", "perut", "susah", "napas", "nafas", "kotoran", "darah");

    /** 英语高频词（功能词 + 常见宠物/症状词）。 */
    private static final Set<String> EN = Set.of(
            "the", "is", "are", "my", "your", "cat", "dog", "and", "has", "have", "had",
            "what", "should", "would", "could", "do", "does", "vomiting", "vomit",
            "diarrhea", "diarrhoea", "not", "with", "for", "been", "sick", "please", "help",
            "today", "eating", "drinking", "eat", "drink", "blood", "stool", "days", "hours",
            "since", "very", "looks", "seems", "still", "normal", "fever", "weak",
            "lethargic", "breathing", "stomach", "eyes", "water", "food");

    /**
     * 检测症状文本语言。
     *
     * @return {@code "id"} / {@code "en"}；判断不出（其它语言 / 过短 / 命中持平）返回 {@code null}。
     */
    public static String detect(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        int id = 0;
        int en = 0;
        for (String token : text.toLowerCase(Locale.ROOT).split("[^a-z]+")) {
            if (token.isEmpty()) {
                continue;
            }
            if (ID.contains(token)) {
                id++;
            } else if (EN.contains(token)) {
                en++;
            }
        }
        if (id > en) {
            return "id";
        }
        if (en > id) {
            return "en";
        }
        return null; // 持平（含 0:0）→ 交调用方回落 locale
    }
}
