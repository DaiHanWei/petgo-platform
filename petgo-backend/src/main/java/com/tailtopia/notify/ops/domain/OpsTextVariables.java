package com.tailtopia.notify.ops.domain;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 运营通知文案变量（V1.4.2 Story 7.1 · AB-37 · 架构 D-16）。纯函数，无 Spring 依赖，L0 可测。
 *
 * <p>存储令牌固定 {@value #NICKNAME} / {@value #PET_NAME}（与后台界面语言无关）：活动表存带令牌的原文，
 * 发送时由 {@link #render} 逐人替换。字数口径：每个令牌按 {@value #VAR_WEIGHT} 计，其余按 UTF-16 长度
 * （{@link String#length()}，与前端 JS {@code .length} 同口径）。
 *
 * <p>🔒 本类不记日志；调用方也不得记替换前后的标题 / 正文、昵称、宠物名。
 */
public final class OpsTextVariables {

    public static final String NICKNAME = "{{nickname}}";
    public static final String PET_NAME = "{{petName}}";

    /** 每个变量在字数校验里按 20 计（= 昵称 / 宠物名列长上限）。 */
    public static final int VAR_WEIGHT = 20;

    /** 空昵称兜底：ID 文案「Sobat」、EN 文案「there」（PRD AC4）。 */
    public static final String FALLBACK_NICKNAME_ID = "Sobat";
    public static final String FALLBACK_NICKNAME_EN = "there";

    /**
     * 变量的三语显示名（= {@code admin.opsNotify.var.tag.*} 去括号；由 {@code OpsTextVariablesMessagesTest} 守卫一致）。
     * 运营在输入框里手打「{用户昵称}」这类文字不会被替换，服务端一律拦下（{@link #containsTyped}）。
     */
    public static final List<String> DISPLAY_NAMES = List.of(
            "用户昵称", "宠物昵称", "User nickname", "Pet name", "Nama panggilan pengguna", "Nama hewan");

    /** 两个合法令牌（单趟替换，替换值里即使含令牌字样也不会被二次替换）。 */
    private static final Pattern TOKEN = Pattern.compile("\\{\\{(nickname|petName)\\}\\}");
    /** 任意 {@code {{…}}}：不是两个合法令牌之一即视为手打 / 伪造。 */
    private static final Pattern ANY_DOUBLE_BRACE = Pattern.compile("\\{\\{([^{}]*)\\}\\}");
    /** 显示名带半角 / 全角花括号，忽略大小写、括号内首尾空白（含全角空格）。 */
    private static final Pattern TYPED_DISPLAY = buildTypedDisplay();

    private OpsTextVariables() {
    }

    private static Pattern buildTypedDisplay() {
        StringBuilder alt = new StringBuilder();
        for (String name : DISPLAY_NAMES) {
            if (alt.length() > 0) {
                alt.append('|');
            }
            // 词间空白放宽为任意空白
            String[] words = name.split(" ");
            for (int i = 0; i < words.length; i++) {
                if (i > 0) {
                    alt.append("[\\s\\u3000]+");
                }
                alt.append(Pattern.quote(words[i]));
            }
        }
        return Pattern.compile("[{\\uFF5B][\\s\\u3000]*(?:" + alt + ")[\\s\\u3000]*[}\\uFF5D]",
                Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    }

    /** 任一文本含 {@value #PET_NAME}。 */
    public static boolean usesPet(String... texts) {
        return containsAny(PET_NAME, texts);
    }

    /** 任一文本含 {@value #NICKNAME}。 */
    public static boolean usesNickname(String... texts) {
        return containsAny(NICKNAME, texts);
    }

    /** 活动四个文案字段（标题 / 正文 × ID / EN）任一用了宠物变量。 */
    public static boolean usesPet(OpsNotificationCampaign c) {
        return usesPet(c.getTitleId(), c.getTitleEn(), c.getBodyId(), c.getBodyEn());
    }

    private static boolean containsAny(String token, String... texts) {
        if (texts == null) {
            return false;
        }
        for (String t : texts) {
            if (t != null && t.contains(token)) {
                return true;
            }
        }
        return false;
    }

    /** 计数长度：每个令牌按 {@value #VAR_WEIGHT}，其余按 {@link String#length()}；{@code null} → 0。 */
    public static int countedLength(String text) {
        if (text == null) {
            return 0;
        }
        int len = text.length();
        Matcher m = TOKEN.matcher(text);
        while (m.find()) {
            len += VAR_WEIGHT - (m.end() - m.start());
        }
        return len;
    }

    /**
     * 逐人替换。{@value #NICKNAME} → 昵称（{@code null} / 去空白后为空 → EN {@value #FALLBACK_NICKNAME_EN}、
     * ID {@value #FALLBACK_NICKNAME_ID}）；{@value #PET_NAME} → 宠物名。同一令牌多次出现全部替换；替换值原样插入，
     * 不做转义 / 格式化。
     *
     * @throws IllegalArgumentException 模板含 {@value #PET_NAME} 而 {@code petName == null}（发送核心已先排除无宠用户）
     */
    public static String render(String template, String nickname, String petName, boolean en) {
        if (template == null) {
            return null;
        }
        if (petName == null && template.contains(PET_NAME)) {
            throw new IllegalArgumentException("模板用了宠物变量但收件人没有宠物");
        }
        String nick = nickname == null || nickname.isBlank()
                ? (en ? FALLBACK_NICKNAME_EN : FALLBACK_NICKNAME_ID) : nickname;
        return replace(template, nick, petName);
    }

    /** 令牌换成显示名（列表摘要 / 0 人预览「显示变量原样」用）。 */
    public static String toDisplay(String text, String nicknameLabel, String petLabel) {
        if (text == null) {
            return null;
        }
        return replace(text, nicknameLabel, petLabel);
    }

    private static String replace(String text, String nickname, String petName) {
        Matcher m = TOKEN.matcher(text);
        if (!m.find()) {
            return text;
        }
        StringBuilder sb = new StringBuilder(text.length() + 32);
        do {
            String v = "nickname".equals(m.group(1)) ? nickname : petName;
            m.appendReplacement(sb, Matcher.quoteReplacement(v == null ? "" : v));
        } while (m.find());
        m.appendTail(sb);
        return sb.toString();
    }

    /**
     * 是否含「手打的变量」：① 任何不是两个合法令牌的 {@code {{…}}}；② 三语显示名带半角 {@code {}} 或全角 {@code ｛｝}
     * （忽略大小写、括号内首尾空白）。
     */
    public static boolean containsTyped(String text) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        Matcher m = ANY_DOUBLE_BRACE.matcher(text);
        while (m.find()) {
            String inner = m.group(1);
            if (!"nickname".equals(inner) && !"petName".equals(inner)) {
                return true;
            }
        }
        return TYPED_DISPLAY.matcher(text).find();
    }

    /** 四个字段任一含手打变量。 */
    public static boolean containsTyped(String... texts) {
        if (texts == null) {
            return false;
        }
        for (String t : texts) {
            if (containsTyped(t)) {
                return true;
            }
        }
        return false;
    }
}
