package com.tailtopia.topic.domain;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 话题名规则的<b>唯一实现</b>（V1.4.2 social-topic · Story 4.1 · P-1 · PRD §3.3「话题名规则」）。
 *
 * <p>🔴 <b>禁止在别处另写话题正则</b>：{@code TopicService}、4.2 联想入参、6.1 后台校验一律调本类；
 * App 侧 {@code shared/rich_text/body_tokens.dart} 与本类读同一份样例
 * {@code src/test/resources/fixtures/topic-parse-cases.json}，两边按样例对齐。
 *
 * <h2>规则</h2>
 * <ol>
 *   <li>起点：ASCII {@code #}（U+0023，全角 {@code ＃} 不算）；{@code #} 必须在行首或前一个字符<b>不是</b>话题字符
 *       （{@code abc#def} 不是话题、{@code (#kucing)} 是）。</li>
 *   <li>话题字符：{@code \p{L}}（任何语言字母）、{@code \p{M}}（组合附标，否则天城文 / 泰文会被截断）、
 *       {@code \p{Nd}}（十进制数字）、{@code _}；遇其他任何字符即结束。</li>
 *   <li>取 {@code #} 之后的<b>最长</b>话题字符串；NFC 后码点数不在 2–30 → 整段不是话题（<b>不截断</b>）；
 *       全部为数字 → 不是话题；标准名（小写后）码点数同样不得超过 30。</li>
 *   <li>同帖标准名相同只算 1 个、只占 1 个名额；前 5 个<b>不同</b>标准名有效，第 6 个起为普通文字。
 *       名额与库里状态无关（命中 DISABLED 也占名额）——前后端纯文本可判才能一致。</li>
 * </ol>
 *
 * <p>纯 Java、无 Spring 依赖、无状态。
 */
public final class TopicNameRules {

    /** 单帖最多有效话题数。 */
    public static final int MAX_TOPICS_PER_POST = 5;

    /** 话题名最短码点数（NFC 后）。 */
    public static final int MIN_LENGTH = 2;

    /** 话题名最长码点数（NFC 后）。 */
    public static final int MAX_LENGTH = 30;

    private TopicNameRules() {
    }

    /**
     * 正文中<b>所有</b>合法话题出现位置（按出现顺序）；同一话题多次出现每处都返回、{@code effective} 相同。
     */
    public static List<TopicToken> parse(String text) {
        if (text == null || text.isEmpty()) {
            return List.of();
        }
        List<TopicToken> out = new ArrayList<>();
        Set<String> effectiveNorms = new LinkedHashSet<>();
        Set<String> overflowNorms = new LinkedHashSet<>();
        int n = text.length();
        int i = 0;
        while (i < n) {
            if (text.charAt(i) != '#' || !atWordStart(text, i)) {
                i++;
                continue;
            }
            int bodyStart = i + 1;
            int j = bodyStart;
            while (j < n) {
                int cp = text.codePointAt(j);
                if (!isTopicChar(cp)) {
                    break;
                }
                j += Character.charCount(cp);
            }
            String raw = text.substring(bodyStart, j);
            if (isValidName(raw)) {
                String norm = normalize(raw);
                boolean effective;
                if (effectiveNorms.contains(norm)) {
                    effective = true;
                } else if (overflowNorms.contains(norm)) {
                    effective = false;
                } else if (effectiveNorms.size() < MAX_TOPICS_PER_POST) {
                    effectiveNorms.add(norm);
                    effective = true;
                } else {
                    overflowNorms.add(norm);
                    effective = false;
                }
                out.add(new TopicToken(i, j, raw, norm, effective));
            }
            // 话题体（无论合法与否）整段跳过；空体时 j == bodyStart，下一个 # 由循环重新判定（##kucing 取第二个）。
            i = Math.max(j, i + 1);
        }
        return List.copyOf(out);
    }

    /** 前 5 个不同标准名（取首次出现那一处，{@code display} 为首次写法），按出现顺序。 */
    public static List<TopicToken> effectiveDistinct(String text) {
        List<TopicToken> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (TopicToken t : parse(text)) {
            if (t.effective() && seen.add(t.norm())) {
                out.add(t);
            }
        }
        return List.copyOf(out);
    }

    /** 标准名：{@code NFC} 后 {@code toLowerCase(Locale.ROOT)}（与默认 Locale 无关）。幂等。 */
    public static String normalize(String raw) {
        return Normalizer.normalize(raw, Normalizer.Form.NFC).toLowerCase(Locale.ROOT);
    }

    /**
     * 话题名（不含 {@code #}）是否合法：全部为话题字符、NFC 后 2–30 码点、不全为数字。
     * 供 6.1 后台新建 / 4.2 联想入参校验复用。
     */
    public static boolean isValidName(String raw) {
        if (raw == null || raw.isEmpty()) {
            return false;
        }
        String nfc = Normalizer.normalize(raw, Normalizer.Form.NFC);
        int count = nfc.codePointCount(0, nfc.length());
        if (count < MIN_LENGTH || count > MAX_LENGTH) {
            return false;
        }
        boolean allDigits = true;
        for (int k = 0; k < nfc.length(); ) {
            int cp = nfc.codePointAt(k);
            if (!isTopicChar(cp)) {
                return false;
            }
            if (Character.getType(cp) != Character.DECIMAL_DIGIT_NUMBER) {
                allDigits = false;
            }
            k += Character.charCount(cp);
        }
        if (allDigits) {
            return false;
        }
        // 小写可能变长（如 U+0130 'İ' → "i̇" 两个码点）：标准名同样不得超过 30，否则落不进 name_norm VARCHAR(30)。
        String norm = nfc.toLowerCase(Locale.ROOT);
        return norm.codePointCount(0, norm.length()) <= MAX_LENGTH;
    }

    /** 话题字符：{@code [\p{L}\p{M}\p{Nd}_]}。 */
    static boolean isTopicChar(int cp) {
        if (cp == '_') {
            return true;
        }
        if (Character.isLetter(cp)) {
            return true;
        }
        int type = Character.getType(cp);
        return type == Character.NON_SPACING_MARK
                || type == Character.COMBINING_SPACING_MARK
                || type == Character.ENCLOSING_MARK
                || type == Character.DECIMAL_DIGIT_NUMBER;
    }

    /** {@code #} 在行首（文本开头）或前一个码点不是话题字符。 */
    private static boolean atWordStart(String text, int hashIndex) {
        if (hashIndex == 0) {
            return true;
        }
        int prev = text.codePointBefore(hashIndex);
        return !isTopicChar(prev);
    }
}
