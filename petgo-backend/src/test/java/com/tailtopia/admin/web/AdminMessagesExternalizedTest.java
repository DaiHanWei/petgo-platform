package com.tailtopia.admin.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * L0 架构守护：管理后台的<b>动态文案</b>（操作提示 / 业务报错）必须走 i18n，不得回潮成硬编码中文。
 *
 * <p>为什么要盯：外化是一次性动作，回潮是持续的——下一个人加个后台接口，顺手写
 * {@code flash.addFlashAttribute("notice", "已保存")} 是最自然的写法，评审也未必看得出。
 * 结果就是后台切到印尼语后，菜单是印尼语、一点按钮弹中文。这个测试让那种写法在 CI 上直接红。
 *
 * <p>扫的是源码文本（surefire 工作目录 = 模块根），纯文件读取，无 Spring / DB。
 */
class AdminMessagesExternalizedTest {

    private static final Path ADMIN = Path.of("src", "main", "java", "com", "tailtopia", "admin");

    /** 中日韩统一表意文字 —— 出现在字符串字面量里即视为未外化的文案。 */
    private static final Pattern CJK = Pattern.compile("[\\u4e00-\\u9fa5]");

    /** {@code addFlashAttribute("notice"|"error"|"toast", ...)} 直到本语句结束。 */
    private static final Pattern FLASH = Pattern.compile(
            "addFlashAttribute\\(\\s*\"(?:notice|error|toast)\"\\s*,(.*?)\\);", Pattern.DOTALL);

    /** {@code AppException.xxx(...)} 直到本语句结束（含可能跟着的 .code(...)）。 */
    private static final Pattern APP_EX = Pattern.compile(
            "AppException\\.\\w+\\((.*?)\\);", Pattern.DOTALL);

    private List<Path> adminSources() throws IOException {
        assertThat(ADMIN).as("需在模块根运行（surefire 默认）").isDirectory();
        try (Stream<Path> s = Files.walk(ADMIN)) {
            return s.filter(p -> p.getFileName().toString().endsWith(".java")).sorted().toList();
        }
    }

    /**
     * 后台闪现提示（操作成功 / 失败横幅）不得含中文字面量——一律 {@code msg.get("admin.flash.…")}。
     */
    @Test
    void adminFlashMessagesAreAllExternalized() throws IOException {
        List<String> offenders = new ArrayList<>();
        for (Path p : adminSources()) {
            Matcher m = FLASH.matcher(Files.readString(p, StandardCharsets.UTF_8));
            while (m.find()) {
                if (CJK.matcher(m.group(1)).find()) {
                    offenders.add(p + " → " + oneLine(m.group()));
                }
            }
        }
        assertThat(offenders)
                .as("后台 flash 提示里还有硬编码中文；改用 msg.get(\"admin.flash.…\") 并补三语文案")
                .isEmpty();
    }

    /**
     * 后台抛出的业务异常，凡带中文文案的都必须挂 {@code .code("admin.err.…")}。
     *
     * <p>原文照留是有意的（日志与兜底），所以这里查的不是「有没有中文」，而是「有中文却没挂码」。
     */
    @Test
    void adminAppExceptionsCarryAMessageCode() throws IOException {
        List<String> offenders = new ArrayList<>();
        for (Path p : adminSources()) {
            Matcher m = APP_EX.matcher(Files.readString(p, StandardCharsets.UTF_8));
            while (m.find()) {
                String stmt = m.group();
                // 只判「有没有挂码」，不判码是不是字面量——requireText 那类共用校验会把码作为参数传进来
                // （见 AdminShopProductService#requireText）。码本身三语是否齐备由下一个用例把关。
                if (CJK.matcher(stmt).find() && !stmt.contains(".code(")) {
                    offenders.add(p + " → " + oneLine(stmt));
                }
            }
        }
        assertThat(offenders)
                .as("后台异常带中文文案却未挂码；补 .code(\"admin.err.…\") 并补三语文案")
                .isEmpty();
    }

    /** 代码里引用的每个 {@code admin.flash.*} / {@code admin.err.*} 码，三语都必须有值。 */
    @Test
    void everyReferencedCodeExistsInAllLocales() throws IOException {
        // 匹配任何位置出现的码字面量：既覆盖 msg.get("…") / .code("…")，
        // 也覆盖以普通参数形式传下去的（如 requireText(v, label, "admin.err.product.nameRequired")）。
        Pattern ref = Pattern.compile("\"((?:admin\\.flash|admin\\.err)\\.[a-zA-Z0-9_.]+)\"");
        TreeSet<String> referenced = new TreeSet<>();
        for (Path p : adminSources()) {
            Matcher m = ref.matcher(Files.readString(p, StandardCharsets.UTF_8));
            while (m.find()) {
                referenced.add(m.group(1));
            }
        }
        assertThat(referenced).as("应扫到后台动态文案码").isNotEmpty();

        for (String locale : List.of("zh_CN", "en", "id")) {
            Properties props = new Properties();
            try (InputStream in = getClass().getResourceAsStream("/i18n/messages_" + locale + ".properties")) {
                assertThat(in).isNotNull();
                props.load(new InputStreamReader(in, StandardCharsets.UTF_8));
            }
            TreeSet<String> missing = new TreeSet<>();
            for (String code : referenced) {
                if (props.getProperty(code) == null) {
                    missing.add(code);
                }
            }
            assertThat(missing).as(locale + " 缺少代码里引用的文案码").isEmpty();
        }
    }

    /** 反向：定义了却没人用的动态文案码 —— 属死键，该删或该接上。 */
    @Test
    void noOrphanDynamicMessageCodes() throws IOException {
        StringBuilder all = new StringBuilder();
        // ⚠️ 引用扫描范围是**整个主源码树**，不是只有 admin 包 —— 后台可达的共享服务
        // （content/auth/moderation 等包，2026-09-02 批量挂码）也持有 admin.err.* 文案码。
        Path mainRoot = Path.of("src", "main", "java", "com", "tailtopia");
        try (Stream<Path> s = Files.walk(mainRoot)) {
            for (Path p : s.filter(x -> x.getFileName().toString().endsWith(".java")).toList()) {
                all.append(Files.readString(p, StandardCharsets.UTF_8));
            }
        }
        Properties zh = new Properties();
        try (InputStream in = getClass().getResourceAsStream("/i18n/messages_zh_CN.properties")) {
            zh.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        }
        TreeSet<String> orphans = new TreeSet<>();
        for (String key : zh.stringPropertyNames()) {
            if ((key.startsWith("admin.flash.") || key.startsWith("admin.err."))
                    && !all.toString().contains('"' + key + '"')) {
                orphans.add(key);
            }
        }
        assertThat(orphans).as("定义了但代码里没引用的动态文案码（死键）").isEmpty();
    }

    /**
     * 🔴 <b>模板里 {@code #{…}} 引用的 key，四个包都必须有值</b>（V1.3.0 Story 10.1 补网）。
     *
     * <h2>这条补的是一个真实的空档</h2>
     * 上面 {@link #everyReferencedCodeExistsInAllLocales} 只扫 <b>java 源码</b>里的
     * {@code admin.flash.*} / {@code admin.err.*}；{@code check-i18n-keys.sh} 只比对
     * <b>四个包之间</b>的 key 集合是否相等。两者交集之外是一大片空地：
     * <b>模板里写了一个谁都没定义的 key</b> —— 四个包一致地都没有它，集合比对当然是绿的；
     * java 侧也扫不到它。
     *
     * <p>后果是运行期 Thymeleaf 原样吐出 {@code ??admin.v130.xxx_zh_CN??} 到页面上，
     * <b>不抛异常、不进日志</b>，渲染冒烟也只验「不报错」。本 story 开发时就真的漏过一个
     * （{@code admin.v130.shopReturns.step.rejected}，模板写了、四个包都没加），
     * 当时 L0 1850 条全绿。
     *
     * <p>两种形态分开判：
     * <ul>
     *   <li><b>字面 key</b>（{@code #{a.b.c}} / {@code #{a.b.c(参数)}}）→ 必须逐字存在；</li>
     *   <li><b>拼前缀</b>（{@code #{'a.b.' + ${x}}}，枚举文案的标准写法）→ 无法静态求值，
     *       改判「该前缀下至少得有一个 key」。它抓不到「少了某一个枚举值」，
     *       但抓得到<b>整个前缀写错 / 整族忘了加</b>，而那正是拼前缀写法最常见的错法。</li>
     * </ul>
     * 完全动态的 {@code #{${x}}}（如公共页签片段的 {@code labelKey}）跳过 —— 无从判起。
     */
    @Test
    void everyMessageKeyReferencedByAdminTemplatesExistsInAllLocales() throws IOException {
        Path templates = Path.of("src", "main", "resources", "templates", "admin");
        // #{a.b.c} / #{a.b.c(...)}：key 后面只允许紧跟 } 或 (
        Pattern literal = Pattern.compile("#\\{([a-zA-Z][a-zA-Z0-9_.]*[a-zA-Z0-9_])\\s*[({]?");
        // #{'a.b.' + ${...}}：取单引号里的前缀
        Pattern prefixed = Pattern.compile("#\\{\\s*'([a-zA-Z][a-zA-Z0-9_.]*\\.)'\\s*\\+");

        TreeSet<String> literalKeys = new TreeSet<>();
        TreeSet<String> prefixes = new TreeSet<>();
        try (Stream<Path> s = Files.walk(templates)) {
            for (Path p : s.filter(x -> x.toString().endsWith(".html")).sorted().toList()) {
                // 注释里贴一段示例写法是常事，连注释一起扫会误报
                String html = Files.readString(p, StandardCharsets.UTF_8)
                        .replaceAll("(?s)<!--.*?-->", "");
                Matcher m = literal.matcher(html);
                while (m.find()) {
                    literalKeys.add(m.group(1));
                }
                Matcher pm = prefixed.matcher(html);
                while (pm.find()) {
                    prefixes.add(pm.group(1));
                }
            }
        }
        assertThat(literalKeys).as("一个字面 key 都没扫到 —— 路径或正则不对，这条此刻毫无意义")
                .isNotEmpty();
        assertThat(prefixes).as("一个拼前缀写法都没扫到 —— 枚举文案是后台的常规写法，扫不到说明正则坏了")
                .isNotEmpty();

        // 默认包（无后缀）也要查：语言协商回落到它时同样会露出 ??key??
        Map<String, String> packs = new LinkedHashMap<>();
        packs.put("zh_CN", "/i18n/messages_zh_CN.properties");
        packs.put("en", "/i18n/messages_en.properties");
        packs.put("id", "/i18n/messages_id.properties");
        packs.put("default", "/i18n/messages.properties");
        for (Map.Entry<String, String> pack : packs.entrySet()) {
            String locale = pack.getKey();
            Properties props = localeProps(pack.getValue());
            TreeSet<String> missing = new TreeSet<>();
            for (String key : literalKeys) {
                if (props.getProperty(key) == null) {
                    missing.add(key);
                }
            }
            assertThat(missing)
                    .as("🔴 " + locale + " 缺少模板里 #{…} 引用的 key —— 页面上会原样渲染出 "
                            + "??key_" + locale + "??，不抛异常、不进日志，渲染冒烟也照样绿")
                    .isEmpty();

            TreeSet<String> emptyPrefixes = new TreeSet<>();
            for (String prefix : prefixes) {
                if (props.stringPropertyNames().stream().noneMatch(k -> k.startsWith(prefix))) {
                    emptyPrefixes.add(prefix);
                }
            }
            assertThat(emptyPrefixes)
                    .as("🔴 " + locale + " 里这些前缀下一个 key 都没有 —— 模板在用 #{'前缀' + ${枚举}} "
                            + "渲染一族文案，而整族都不存在")
                    .isEmpty();
        }
    }

    private Properties localeProps(String resource) throws IOException {
        Properties props = new Properties();
        try (InputStream in = getClass().getResourceAsStream(resource)) {
            assertThat(in).as("读不到 " + resource).isNotNull();
            props.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        }
        return props;
    }

    private static String oneLine(String s) {
        return s.replaceAll("\\s+", " ").trim();
    }
}
