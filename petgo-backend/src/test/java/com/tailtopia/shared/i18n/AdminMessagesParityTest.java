package com.tailtopia.shared.i18n;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

/**
 * L0：后台 messages 键集对齐（Story 1.6 AC3 起，Story 11.7 AC4 扩为三语 + 英文基线）。
 *
 * <p>为什么要硬测：后台文案是**运营的操作依据**。缺一条键，界面上露出的是 {@code admin.tags.icon}
 * 这样的原始键名——运营看不懂就会猜，猜错了去点「封号」或「结算」。所以键集不是整洁度问题。
 *
 * <p>断言分两层，缺一层都会漏掉真实事故：
 * <ol>
 *   <li><b>三语两两相等</b>——任一语言单边缺键即红。加语言时不会「只补一半」。</li>
 *   <li><b>基线是超集</b>——{@code messages.properties}（英文基线）必须覆盖三语所有键。
 *       它是回退链 {@code messages_<locale>} → {@code messages} → code 的中间层；
 *       基线漏键 = 那条键的回退直接掉到 code，等于没有基线。</li>
 * </ol>
 */
class AdminMessagesParityTest {

    /** 三语文件（不含基线——基线单独按超集校验，不参与两两相等）。 */
    private static final Map<String, String> LOCALES = new LinkedHashMap<>();

    static {
        LOCALES.put("zh_CN", "/i18n/messages_zh_CN.properties");
        LOCALES.put("en", "/i18n/messages_en.properties");
        LOCALES.put("id", "/i18n/messages_id.properties");
    }

    private static final String BASELINE = "/i18n/messages.properties";

    private Properties load(String path) throws Exception {
        Properties p = new Properties();
        try (InputStream in = getClass().getResourceAsStream(path)) {
            assertThat(in).as("缺少 i18n 资源 " + path).isNotNull();
            p.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        }
        return p;
    }

    private Set<String> keys(String path) throws Exception {
        return new TreeSet<>(load(path).stringPropertyNames());
    }

    @Test
    void allLocaleKeySetsAreIdentical() throws Exception {
        Map<String, Set<String>> byLocale = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : LOCALES.entrySet()) {
            Set<String> keys = keys(e.getValue());
            assertThat(keys).as(e.getKey() + " 键集为空").isNotEmpty();
            byLocale.put(e.getKey(), keys);
        }

        // 以 zh_CN 为基准逐语言比对：报错信息要直接给出「哪个语言缺哪几条」，
        // 而不是只说两个 Set 不等——补键的人需要的是清单。
        Set<String> reference = byLocale.get("zh_CN");
        for (Map.Entry<String, Set<String>> e : byLocale.entrySet()) {
            if ("zh_CN".equals(e.getKey())) {
                continue;
            }
            Set<String> missing = new TreeSet<>(reference);
            missing.removeAll(e.getValue());
            Set<String> extra = new TreeSet<>(e.getValue());
            extra.removeAll(reference);

            assertThat(missing).as(e.getKey() + " 缺失的键（zh_CN 有）").isEmpty();
            assertThat(extra).as(e.getKey() + " 多出的键（zh_CN 没有）").isEmpty();
        }
    }

    @Test
    void baselineCoversEveryLocaleKey() throws Exception {
        Set<String> baseline = keys(BASELINE);
        for (Map.Entry<String, String> e : LOCALES.entrySet()) {
            Set<String> uncovered = new TreeSet<>(keys(e.getValue()));
            uncovered.removeAll(baseline);
            assertThat(uncovered)
                    .as(e.getKey() + " 有而英文基线 messages.properties 缺的键（回退会掉到键名）")
                    .isEmpty();
        }
    }

    @Test
    void noBlankValues() throws Exception {
        Map<String, String> all = new LinkedHashMap<>(LOCALES);
        all.put("baseline", BASELINE);
        for (Map.Entry<String, String> e : all.entrySet()) {
            load(e.getValue())
                    .forEach((k, v) ->
                            assertThat(v.toString().trim())
                                    .as(e.getKey() + " 空值 " + k)
                                    .isNotEmpty());
        }
    }

    /**
     * 占位符 {@code {0}} / {@code {1}} …… 的编号集合必须逐键一致。
     *
     * <p>这条比「键存在」更难发现也更危险：印尼语句子的语序和中文不同，翻译时很容易把
     * {@code {0}} 漏掉或写成 {@code {O}}。漏掉的后果不是排版难看，而是
     * 「已用 {0} / {1} 个」变成「已用 / 个」——运营据此判断额度就会判错。
     */
    @Test
    void placeholderIndexesMatchAcrossLocales() throws Exception {
        Properties zh = load(LOCALES.get("zh_CN"));
        for (Map.Entry<String, String> e : LOCALES.entrySet()) {
            if ("zh_CN".equals(e.getKey())) {
                continue;
            }
            Properties other = load(e.getValue());
            for (String key : new TreeSet<>(zh.stringPropertyNames())) {
                assertThat(placeholders(other.getProperty(key, "")))
                        .as(e.getKey() + " 占位符与 zh_CN 不一致：" + key)
                        .isEqualTo(placeholders(zh.getProperty(key)));
            }
        }
    }

    /**
     * 🛡 AC2：{@code WIB} 字样不翻译。
     *
     * <p>既有口径是「时间一律显式标 WIB」。把 WIB 译成 {@code WIT}/{@code Waktu Indonesia} 之类，
     * 运营就要自己换算时区——排期差一小时的后果是内容在错误时间发出去。
     */
    @Test
    void wibIsNeverTranslated() throws Exception {
        Properties zh = load(LOCALES.get("zh_CN"));
        Set<String> wibKeys = new TreeSet<>();
        for (String k : zh.stringPropertyNames()) {
            if (zh.getProperty(k).contains("WIB")) {
                wibKeys.add(k);
            }
        }
        // 断言基数：万一 zh 侧哪天把 WIB 删干净了，下面的循环会变成空跑。
        assertThat(wibKeys).as("zh 侧带 WIB 的键").hasSizeGreaterThanOrEqualTo(13);

        for (Map.Entry<String, String> e : LOCALES.entrySet()) {
            Properties other = load(e.getValue());
            for (String k : wibKeys) {
                assertThat(other.getProperty(k))
                        .as(e.getKey() + " 把 WIB 译掉了：" + k)
                        .contains("WIB");
            }
        }
    }

    /**
     * 🛡 AC2：文案值里出现的**权限码必须逐字保留**，不许被翻译。
     *
     * <p>当前有一处真实占用：{@code admin.config.pricingReadonly} 的文案里写着
     * 「需 {@code config.edit} 权限修改」——那是**故意**告诉运营该找谁要权限的。
     * 权限码一旦落地就**冻结**，译过的码对不上任何真实权限，运营拿着它去申请会被驳回。
     *
     * <p>比对基准取自 {@link com.tailtopia.admin.account.domain.AdminPermissions} 的常量，
     * 而不是靠正则猜——这样 {@code x.jpg} / {@code e.g} 这类点分文本不会被误判。
     */
    @Test
    void permissionCodesInValuesSurviveTranslation() throws Exception {
        Set<String> codes = new TreeSet<>();
        for (java.lang.reflect.Field f :
                com.tailtopia.admin.account.domain.AdminPermissions.class.getDeclaredFields()) {
            if (java.lang.reflect.Modifier.isStatic(f.getModifiers())
                    && f.getType() == String.class) {
                f.setAccessible(true);
                codes.add((String) f.get(null));
            }
        }
        assertThat(codes).as("权限码常量").isNotEmpty();

        Properties zh = load(LOCALES.get("zh_CN"));
        Map<String, Set<String>> expected = new LinkedHashMap<>();
        for (String k : zh.stringPropertyNames()) {
            Set<String> found = new TreeSet<>();
            for (String code : codes) {
                if (zh.getProperty(k).contains(code)) {
                    found.add(code);
                }
            }
            if (!found.isEmpty()) {
                expected.put(k, found);
            }
        }
        // 断言基数：真有这么一处，否则下面的循环是空跑。
        assertThat(expected).as("zh 侧文案里嵌了权限码的键").isNotEmpty();

        Map<String, String> all = new LinkedHashMap<>(LOCALES);
        all.put("baseline", BASELINE);
        for (Map.Entry<String, String> e : all.entrySet()) {
            Properties other = load(e.getValue());
            for (Map.Entry<String, Set<String>> exp : expected.entrySet()) {
                assertThat(other.getProperty(exp.getKey(), ""))
                        .as(e.getKey() + " 丢了权限码 " + exp.getValue() + "：" + exp.getKey())
                        .contains(exp.getValue());
            }
        }
    }

    /**
     * 🛡 AC2：文案值里**不得出现 UPPER_SNAKE 枚举值或 snake_case 标识串**。
     *
     * <p>当前是 0 处——这类标识串都在 Java 枚举与常量里，从不进消息文件。这条是**防漏进来**：
     * 一旦有人把 {@code stats_view} 或 {@code PENDING_REVIEW} 粘进说明文案，
     * 三语会各自把它译成不同样子，而它本该是逐字不变的。
     *
     * <p>⚠️ 只管这两种形状。点分权限码（{@code config.edit}）由上一条按常量表比对——
     * 用正则去抓点分串会把 {@code x.jpg} / {@code e.g} 一并误判。
     */
    @Test
    void messageValuesEmbedNoEnumOrSnakeCaseIdentifiers() throws Exception {
        java.util.regex.Pattern upperSnake =
                java.util.regex.Pattern.compile("\\b[A-Z][A-Z0-9]*_[A-Z0-9_]+\\b");
        java.util.regex.Pattern snake =
                java.util.regex.Pattern.compile("\\b[a-z][a-z0-9]*_[a-z0-9_]+\\b");
        Map<String, String> all = new LinkedHashMap<>(LOCALES);
        all.put("baseline", BASELINE);
        for (Map.Entry<String, String> e : all.entrySet()) {
            Properties p = load(e.getValue());
            Set<String> offenders = new TreeSet<>();
            for (String k : p.stringPropertyNames()) {
                String v = p.getProperty(k);
                for (java.util.regex.Pattern pat : java.util.List.of(upperSnake, snake)) {
                    java.util.regex.Matcher m = pat.matcher(v);
                    if (m.find()) {
                        offenders.add(k + " => " + m.group());
                    }
                }
            }
            assertThat(offenders).as(e.getKey() + " 文案值里混进了标识串").isEmpty();
        }
    }

    private Set<String> placeholders(String text) {
        Set<String> out = new TreeSet<>();
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\{(\\d+)}").matcher(text);
        while (m.find()) {
            out.add(m.group(1));
        }
        return out;
    }
}
