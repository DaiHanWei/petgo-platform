package com.tailtopia.shop.review.service;

import java.text.BreakIterator;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 评价公开昵称打码（v1.4.2 shop Story 6.1 复审修复，C-9 + 隐私）。
 *
 * <p>🔒 社区昵称在 Google 注册时初始化为 Google displayName（多为真名），原样公开在评价旁边等于
 * 「真名 + 买了什么（处方粮带健康暗示）」对游客可见。评价出参<b>只用本类打码后的值</b>：
 * <ul>
 *   <li>多词：首词 + 空格 + 最后一词的首字符 + {@code "."}（{@code "Dewi Sartika"} → {@code "Dewi S."}；
 *       中间词丢弃）；</li>
 *   <li>单词：只保留该词；</li>
 *   <li>null / 全空白 → null。</li>
 * </ul>
 * 分词按 Unicode 空白（含不换行空格、全角空格）；「首字符」按字素簇取（emoji / 组合字符不会被劈开）。
 * 格式待产品确认并入决策日志。纯函数，无状态。
 */
public final class ReviewerNameMasker {

    /** Unicode 空白（\s 在 UNICODE_CHARACTER_CLASS 下含 NBSP / 全角空格等）。 */
    private static final Pattern WS = Pattern.compile("[\\s\\p{Z}\\u200B\\uFEFF]+", Pattern.UNICODE_CHARACTER_CLASS);

    private ReviewerNameMasker() {
    }

    public static String mask(String raw) {
        if (raw == null) {
            return null;
        }
        String t = WS.matcher(raw).replaceAll(" ").strip();
        if (t.isEmpty()) {
            return null;
        }
        String[] words = t.split(" ");
        if (words.length == 1) {
            return words[0];
        }
        return words[0] + " " + firstGrapheme(words[words.length - 1]) + ".";
    }

    private static String firstGrapheme(String word) {
        BreakIterator it = BreakIterator.getCharacterInstance(Locale.ROOT);
        it.setText(word);
        int end = it.next();
        return end == BreakIterator.DONE ? word : word.substring(0, end);
    }
}
