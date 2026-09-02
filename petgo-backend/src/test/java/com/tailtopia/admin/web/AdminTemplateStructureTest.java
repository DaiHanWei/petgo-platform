package com.tailtopia.admin.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * L0：后台模板的**结构**护栏 —— 页面上该有的东西必须真的在会被渲染的范围内。
 *
 * <h2>这条守的是一个真实事故（2026-08-26 实机截图发现）</h2>
 * 「首页推荐算法」整块（Story 16.4 交付）写在了 {@code th:fragment="content"} 的
 * <b>闭合标签之外</b>。业务页经 {@code admin/layout :: page(~{::content})} 渲染，
 * 只取那一个片段 ⇒ <b>整块被静默丢弃，从交付起就没在页面上出现过</b>。
 * 连带 Story 17.1 加在同一表单里的「限流系数」也一起不可见 ——
 * 那两条 story 的「后台可配」实际上办不到，运营根本找不到那些参数。
 *
 * <p>⚠️ <b>当时全套测试都是绿的。</b>渲染冒烟只验「不报错」，服务层测试直接调 service，
 * 端点测试直接 POST —— <b>丢掉一整块 HTML 不会让任何一条变红。</b>
 *
 * <h2>为什么是这条，而不是逐页手写断言</h2>
 * 事故当天补的是逐块列举（「运营配置页必须有定价/PawCoin/分享奖励/档位」）。
 * 那种写法只守住被列举的那两页，<b>新加一页、新加一块都不会自动进网</b> ——
 * 同样的事故换个页面还会再来一次，而且下一次同样不会有人先想到回来补一行。
 *
 * <p>本条改成<b>遍历全部模板的结构规则</b>：任何 {@code <form>} / {@code <table>} /
 * {@code data-section} 都必须落在某个 {@code th:fragment} 之内。新模板一落地即受约束，
 * 不依赖谁记得。代价是它只能看结构、不能看运行期（渲染报错、漏译、状态分支）——
 * 那些仍归 {@code AdminPagesRenderSmokeTest} 那条 L1 链路。
 *
 * <p>纯文件扫描，无 Spring / 无 DB。
 */
class AdminTemplateStructureTest {

    private static final Path DIR = Path.of("src", "main", "resources", "templates", "admin");

    /**
     * 独立整页：自己就是完整 HTML、不经 layout 片段拼装，故不受本规则约束。
     *
     * <p>⚠️ 往这里加名字等于给一个页面开豁免 —— 只有「这一页真的不经 layout 渲染」才成立。
     * 「加进来就绿了」不是理由：那恰恰说明它的内容掉在片段外了。
     */
    private static final Set<String> STANDALONE_PAGES = Set.of("login.html");

    private static final Pattern OPEN_TAG = Pattern.compile("<([a-zA-Z][a-zA-Z0-9:._-]*)");

    /**
     * 「实质内容」的锚点。取表单 / 表格 / 显式区块标记三种：它们是运营真正要用的东西
     * （填写、查看、操作），掉在片段外就等于该功能不存在。
     * 不取纯文本与标题 —— 那类丢失顶多是说明缺一句，且会淹掉真正的信号。
     */
    private static final Pattern ANCHOR = Pattern.compile("(<form\\b)|(<table\\b)|(data-section=)");

    /** 一个 {@code th:fragment} 元素占据的行区间（含首尾）。 */
    private record Span(int start, int end) {
        boolean covers(int line) {
            return start <= line && line <= end;
        }
    }

    @Test
    void everyLayoutRenderedTemplateDeclaresAFragment() throws IOException {
        Set<String> offenders = new TreeSet<>();
        for (Path f : templates()) {
            if (STANDALONE_PAGES.contains(fileName(f))) {
                continue;
            }
            if (!Files.readString(f, StandardCharsets.UTF_8).contains("th:fragment=")) {
                offenders.add(fileName(f));
            }
        }
        assertThat(offenders)
                .as("🔴 这些模板经 layout 渲染却没有声明 th:fragment —— 整页内容不会被输出")
                .isEmpty();
    }

    /**
     * 🔴 全部后台模板：表单 / 表格 / 区块标记都必须在某个 {@code th:fragment} 之内。
     *
     * <p>合法的例外只有一种：<b>专门包住片段的外壳</b>。如 {@code content.html} 里那个
     * {@code <table hidden>} 只是让 {@code <tr th:fragment="row">}（HTMX 原地换行用）
     * 成为合法 HTML，它本身不参与整页渲染。判据是「这个元素内部起了一个片段」，
     * 而不是名单豁免 —— 名单会被当成绿灯开关用。
     */
    @Test
    void noRealContentLivesOutsideAnyFragment() throws IOException {
        List<String> offenders = new ArrayList<>();
        for (Path f : templates()) {
            if (STANDALONE_PAGES.contains(fileName(f))) {
                continue;
            }
            List<String> lines = Files.readAllLines(f, StandardCharsets.UTF_8);
            List<Span> fragments = fragmentSpans(lines);
            for (int i = 0; i < lines.size(); i++) {
                final int line = i;
                Matcher m = ANCHOR.matcher(lines.get(i));
                if (!m.find()) {
                    continue;
                }
                if (fragments.stream().anyMatch(s -> s.covers(line))) {
                    continue;
                }
                if (wrapsAFragment(lines, i, m, fragments)) {
                    continue;
                }
                offenders.add(fileName(f) + ":" + (i + 1) + "  " + lines.get(i).trim());
            }
        }
        assertThat(offenders)
                .as("🔴 这些内容在 th:fragment 之外 —— 页面渲染时会被静默丢弃，"
                        + "表现为「功能交付了但运营在界面上找不到」（2026-08-26 事故形态）")
                .isEmpty();
    }

    /**
     * 🔴 结构标签必须**成对闭合**（bug 20260826）。
     *
     * <h2>这条守的是一个真实事故</h2>
     * 侧栏模板里混进过一段合并残留：一个多余的 {@code <details>} 加一个<b>没有闭合的
     * {@code <summary>}</b>，紧接着才是正确的那一组。浏览器会自行「修复」这种 HTML ——
     * 结果是「兽医」整组被塞进上一组的标题里：<b>点不动、收不起来</b>，
     * 侧栏上还多出一个孤零零的空箭头。
     *
     * <p>⚠️ <b>没有任何东西会报错</b>：Thymeleaf 只做属性替换、不校验标签配对；
     * 页面照常 200；渲染冒烟、i18n 扫描、片段结构检查<b>全都是绿的</b>。
     * 只有人打开侧栏点一下才发现收不起来。
     *
     * <p>先剥掉 HTML 注释再数 —— 注释里写着示例标签是常事，连注释一起数会误报。
     */
    @Test
    void blockTagsAreBalanced() throws IOException {
        List<String> offenders = new ArrayList<>();
        for (Path f : templates()) {
            String html = Files.readString(f, StandardCharsets.UTF_8)
                    .replaceAll("(?s)<!--.*?-->", "");
            for (String tag : List.of("details", "summary", "form", "table")) {
                int open = count(Pattern.compile("<" + tag + "\\b"), html);
                int close = count(Pattern.compile("</" + tag + "\\s*>"), html);
                if (open != close) {
                    offenders.add(fileName(f) + ": <" + tag + "> " + open + " 个开标签 / "
                            + close + " 个闭合");
                }
            }
        }
        assertThat(offenders)
                .as("🔴 标签没有成对闭合 —— 浏览器会自行重排 DOM，表现是「某一组莫名其妙嵌进了别人里面、"
                        + "点不动也收不起来」，而服务端一切正常、测试全绿（2026-08-26 侧栏事故形态）")
                .isEmpty();
    }

    /**
     * 🔴 后台**不得直接渲染后端枚举名**（bug 20260826）。
     *
     * 运营看到的是 {@code DAILY} / {@code GROWTH_MOMENT}，而用户在 App 上看到的是
     * {@code Moment} / {@code Diary} —— 运营与用户对不上话，连「用户说的 Diary 是后台哪一种」
     * 都要靠人脑翻译。展示一律走 {@code #{'admin.contentType.' + …}}。
     *
     * <p>⚠️ 只管**展示**。表单 {@code th:value} / Excel 模板列仍是枚举名 —— 那是机器要解析的，
     * 改了会让运营现有的 Excel 文件全部解析失败（解析器按枚举名匹配）。
     */
    @Test
    void templatesNeverPrintRawContentTypeEnum() throws IOException {
        Pattern rawTypeText = Pattern.compile(
                "th:text=\"\\$\\{[A-Za-z]+\\.?type(\\(\\))?}\"");
        List<String> offenders = new ArrayList<>();
        for (Path f : templates()) {
            String html = Files.readString(f, StandardCharsets.UTF_8)
                    .replaceAll("(?s)<!--.*?-->", "");
            Matcher m = rawTypeText.matcher(html);
            while (m.find()) {
                offenders.add(fileName(f) + " → " + m.group());
            }
        }
        assertThat(offenders)
                .as("🔴 直接把内容类型枚举名打到页面上了。运营后台的内容称呼必须与 App 一致，"
                        + "改用 #{'admin.contentType.' + ${…}}")
                .isEmpty();
    }

    /**
     * 🔴 有原生日期选择器的页面，底部必须留出弹层的位置（bug 20260828）。
     *
     * 现象：顶置管理的「生效时间」点开日历后，弹层被窗口底边裁掉，而页面**滚不动** ——
     * 页面本身不够长，浏览器没有可滚的余量，运营只能盲选或去改窗口大小。
     * 原生 {@code datetime-local} 的弹层由浏览器绘制，位置与高度都控制不了，只能给它腾地方。
     *
     * <p>⚠️ 钉的是「这条 CSS 还在」。它是一行容易被当成多余空白删掉的规则，
     * 而删掉之后**页面照常渲染、所有测试照常绿**，只有运营下次点日历时才发现 ——
     * 与本类守的其它几条同一性质。
     */
    @Test
    void pagesWithNativeDatePickersReserveRoomForThePopup() throws IOException {
        List<String> withPicker = new ArrayList<>();
        for (Path f : templates()) {
            if (Files.readString(f, StandardCharsets.UTF_8).contains("datetime-local")) {
                withPicker.add(fileName(f));
            }
        }
        assertThat(withPicker).as("本条的前提是确实有页面用原生日期选择器").isNotEmpty();

        String css = Files.readString(
                Path.of("src", "main", "resources", "static", "admin", "admin.css"),
                StandardCharsets.UTF_8);
        assertThat(css)
                .as("🔴 这些页面用了原生日期选择器：" + withPicker
                        + "，但 admin.css 里没有给弹层留空间的规则 —— "
                        + "日历会被窗口底边裁掉且页面滚不动")
                .contains("input[type=\"datetime-local\"]")
                .contains("padding-bottom");
    }

    /**
     * 🔴 **构建产物里不得有源码树之外的迁移**（切分支残留）。
     *
     * <h2>这条守的是一个反复浪费时间的坑</h2>
     * `mvn` 的 process-resources 只**覆盖**、不删除 —— 在 A 分支打过包再切到 B 分支跑测试，
     * `target/classes/db/migration` 里会留着 A 独有的迁移文件，被当成本分支的一起执行。
     * 报错长这样：
     * <pre>Failed to execute script V105__...sql: column "sex" already exists</pre>
     * 看着像**代码坏了**，实际只需要 `mvn clean`。同一个坑 2026-08-26 到 28 踩了三次，
     * 每次都要先怀疑一遍自己的改动。
     *
     * <p>⚠️ 本条**只在 target 已存在时**比对（先跑过 compile/test）。它不能阻止那次失败，
     * 但能把一个指向错误方向的 Flyway 报错，换成一句「先 mvn clean」。
     */
    @Test
    void buildOutputCarriesNoStaleMigrationsFromAnotherBranch() throws IOException {
        Path built = Path.of("target", "classes", "db", "migration");
        if (!Files.isDirectory(built)) {
            return; // 还没编译过，无从比对
        }
        Set<String> inSource = new TreeSet<>();
        try (Stream<Path> f = Files.list(Path.of("src", "main", "resources", "db", "migration"))) {
            f.forEach(p -> inSource.add(p.getFileName().toString()));
        }
        List<String> stale = new ArrayList<>();
        try (Stream<Path> f = Files.list(built)) {
            f.map(p -> p.getFileName().toString())
                    .filter(n -> n.endsWith(".sql") && !inSource.contains(n))
                    .forEach(stale::add);
        }
        assertThat(stale)
                .as("🔴 target 里有源码树里没有的迁移 —— 多半是在另一条分支打过包后没 clean。"
                        + "它们会被 Flyway 当成本分支的迁移执行，报出指向错误方向的错误"
                        + "（「column already exists」之类）。**先跑 `./mvnw clean`**")
                .isEmpty();
    }

    /**
     * 🔴 后台改名必须**四处一起改**，否则界面会自相矛盾（bug 20260828「装饰标签」→「内容标签」）。
     *
     * <p>一个后台功能的名字散在四个地方：侧栏导航名、页内标题、空态文案、以及
     * 权限清单里那两条显示名。只改导航名的结果是：侧栏写「内容标签」、点进去标题写
     * 「装饰标签」、去权限页勾选时又变回「装饰标签」—— 运营会以为是三个功能。
     *
     * <p>⚠️ 断的是**旧名一个字都不许剩在用户可见文案里**，而不是逐个 key 比对：
     * 逐个比对得在改名时同步维护一份清单，而清单本身就是下一次漏改的地方。
     *
     * <p>⚠️ 只扫 i18n 文案（用户读到的字），**不扫代码与迁移注释** ——
     * 那些是写给开发看的历史脉络，把 Story 5.2 当年的措辞也一并改掉反而丢信息。
     */
    @Test
    void renamedAdminFeaturesLeaveNoOldNameInAnyUserFacingString() throws IOException {
        // 已完成的改名：旧名 → 新名（新增改名时往这里加一行）。
        Map<String, String> renamed = new LinkedHashMap<>();
        renamed.put("装饰标签", "内容标签");
        renamed.put("Decoration tag", "Content tag");
        renamed.put("decoration tag", "content tag");
        renamed.put("Tag dekorasi", "Tag konten");
        renamed.put("tag dekorasi", "tag konten");

        Path i18n = Path.of("src", "main", "resources", "i18n");
        List<String> hits = new ArrayList<>();
        try (Stream<Path> files = Files.list(i18n)) {
            for (Path f : files.filter(p -> p.getFileName().toString().endsWith(".properties")).toList()) {
                List<String> lines = Files.readAllLines(f);
                for (int i = 0; i < lines.size(); i++) {
                    String line = lines.get(i);
                    // 注释行是分节说明，允许保留「由 XX 更名」这类历史脉络。
                    if (line.stripLeading().startsWith("#")) {
                        continue;
                    }
                    for (Map.Entry<String, String> e : renamed.entrySet()) {
                        if (line.contains(e.getKey())) {
                            hits.add(f.getFileName() + ":" + (i + 1) + " 仍写着「" + e.getKey()
                                    + "」，应为「" + e.getValue() + "」 → " + line.strip());
                        }
                    }
                }
            }
        }
        assertThat(hits)
                .as("🔴 改名没改齐 —— 侧栏 / 标题 / 空态 / 权限显示名必须同时换，"
                        + "只改一处会让运营以为是两个不同的功能")
                .isEmpty();
    }

    /**
     * 🔴 **没有可用标签时，「给内容打标」不许渲染一个点不开的空下拉**（bug 20260828）。
     *
     * <p>实机：一个标签都还没建，页面照样把整张打标表单铺出来 —— 一个空的、点开什么都没有的
     * 下拉，底下跟着几十条待选内容。界面上没有任何一句话说明「先去建标签」，
     * 于是它读起来像**坏了**，而不是像**还没准备好**。运营的原话就是「点不开，是空的」。
     *
     * <p>⚠️ 用文本结构断言而不是渲染断言：要渲染出这一屏得先准备一个「零标签」的库，
     * 而这一页的判据只有两条、都写在模板里，扫文本就够，且不依赖 Docker。
     */
    @Test
    void tagAssignFormIsHiddenWhenNoTagExistsYet() throws IOException {
        String html = Files.readString(DIR.resolve("content-tags.html"));

        assertThat(html)
                .as("🔴 打标表单没有「无可用标签时不渲染」的条件 ⇒ 运营会看到一个点不开的空下拉")
                .contains("th:unless=\"${#lists.isEmpty(assignable)}\"");
        assertThat(html)
                .as("🔴 少了「先去建标签」那句提示 ⇒ 表单藏起来之后，那一块变成一片空白，"
                        + "比空下拉更让人不知道该干什么")
                .contains("data-notice=\"assign-needs-tag\"");
        assertThat(html)
                .as("🔴 判据必须是 assignable（**在线**标签）而不是 tags —— "
                        + "标签全部下线时同样打不了标，用 tags 判会漏掉那种情况")
                .doesNotContain("th:unless=\"${#lists.isEmpty(tags)}\"");
    }

    /**
     * 🔴 **走 fetch 上传的页面，自己的 &lt;head&gt; 里必须有那两个 CSRF meta**
     * （2026-09-02 stag 电商测试 D-8）。
     *
     * <h2>这条守的是一个真实事故</h2>
     * {@code shop-banners.html} 与 {@code shop-product-form.html} 漏了这两行 meta。
     * {@code /admin/**} 那条过滤链**保留 CSRF**（见 {@code SecurityConfig}），
     * 表单提交有 Thymeleaf 自动带的隐藏域，**fetch 没有** ——
     * {@code admin.js} 从这两个 meta 里取，取不到就不带头，Spring Security 直接 403。
     * 于是**后台传不了任何 banner、也传不了商品主图**，而运营看到的只是「选了图没反应」。
     *
     * <p>⚠️ <b>当时全套测试都是绿的</b>：页面照常 200、片段结构合法、i18n 齐全、
     * 上传接口自己的测试也过（那些测试都 {@code .with(csrf())}，正好把这个洞盖住了）。
     * 唯一能发现它的方式是真的用浏览器传一张图。
     *
     * <p>⚠️ <b>不能靠「挪进 layout.html 统一注入」来根治</b>：业务页用
     * {@code th:replace="~{admin/layout :: page(~{::content})}"} 只换掉 body 里那个 div，
     * <b>layout 的 &lt;head&gt; 根本不会被渲染</b>，各页保留自己的 head
     * （layout 注释亦言明「各业务页在自身 head 引入」）。所以只能逐页写，
     * 也正因为只能逐页写，才需要这条测试兜着 —— 下一个加上传的页面同样会漏。
     */
    @Test
    void everyFetchUploadPageDeclaresCsrfMeta() throws IOException {
        List<String> withUploader = new ArrayList<>();
        List<String> offenders = new ArrayList<>();
        for (Path f : templates()) {
            String html = Files.readString(f, StandardCharsets.UTF_8);
            if (!html.contains("data-upload-url")) {
                continue;
            }
            withUploader.add(fileName(f));
            // 两个都要：admin.js 里是 `if (token && header)`，缺任一条就整体不带头。
            if (!html.contains("name=\"_csrf\"") || !html.contains("name=\"_csrf_header\"")) {
                offenders.add(fileName(f));
            }
        }
        assertThat(withUploader).as("本条的前提是确实有页面走 fetch 上传").isNotEmpty();
        assertThat(offenders)
                .as("🔴 这些页面走 fetch 上传却没在自己的 <head> 里放 CSRF meta ⇒ "
                        + "请求不带 CSRF 头 → 403 → 运营看到的是「选了图没反应」。"
                        + "补上 seed-post.html 里那两行 <meta name=\"_csrf\"…>（不能靠 layout 统一注入，"
                        + "layout 的 head 不参与业务页渲染）")
                .isEmpty();
    }

    /**
     * 🔴 **上传失败必须有落点**（2026-09-02 stag 电商测试 D-8 第 3 条）。
     *
     * <p>D-8 之所以从「少两行 meta」拖成「完全不可用且查不出原因」，是因为错误在前端被吞了：
     * 批次那条 {@code reject()} 首行是 {@code if (!box) { return; }} —— 容器不在就静默 return；
     * 单条那条 {@code showError()} 直接 {@code .appendChild} —— 容器不在就抛异常，
     * 而它在 fetch 的 then/catch 里，抛出去只是一条 unhandled rejection。两种写法，同一个后果：
     * <b>界面上一个字都没有</b>。
     *
     * <p>现已统一走 {@code adminUploadError()} 逐级回退，最后一级必定 {@code console.error}。
     * 本条钉住「不许再退回静默」—— 这类代码删一行就恢复原状，且删完所有测试照样绿。
     */
    @Test
    void uploadErrorsAreNeverSwallowedSilently() throws IOException {
        // ⚠️ 只看**代码行**：本文件的注释里逐字引用了那句被废弃的写法（讲清楚当初错在哪），
        //    连注释一起扫会把说明文字本身判成违规 —— 与 blockTagsAreBalanced 先剥注释同理。
        String js = Files.readString(
                        Path.of("src", "main", "resources", "static", "admin", "admin.js"),
                        StandardCharsets.UTF_8)
                .lines()
                .filter(l -> !l.strip().startsWith("//"))
                .collect(java.util.stream.Collectors.joining("\n"));

        assertThat(js)
                .as("🔴 上传错误的统一落点 adminUploadError() 不在了 —— "
                        + "两个上传控件会各自退回「认死一个容器」的老写法")
                .contains("function adminUploadError(");
        assertThat(js)
                .as("🔴 兜底那条 console.error 没了 ⇒ 容器缺失时又变成一声不吭。"
                        + "宁可只有 F12 里看得到，也不能静默")
                .contains("console.error('[admin upload] '");
        assertThat(js)
                .as("🔴 又有人把「容器不在就 return」写回来了 —— 这正是 D-8 里吞掉全部错误的那一行")
                .doesNotContain("if (!box) { return; }");
    }

    /** 该锚点元素内部起了一个片段 ⇒ 它是片段的外壳，不参与整页渲染，合法。 */
    private boolean wrapsAFragment(List<String> lines, int line, Matcher anchor, List<Span> fragments) {
        String tag = anchor.group(1) != null ? "form"
                : anchor.group(2) != null ? "table"
                        : ownerTag(lines, line, anchor.start(3));
        if (tag == null) {
            return false;
        }
        int end = closingLine(lines, line, tag);
        return fragments.stream().anyMatch(s -> line <= s.start() && s.start() <= end);
    }

    /** 全部 {@code th:fragment} 元素的行区间。片段可挂在任意标签上（div / tr / th:block…）。 */
    private List<Span> fragmentSpans(List<String> lines) {
        List<Span> out = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            int col = lines.get(i).indexOf("th:fragment=");
            if (col < 0) {
                continue;
            }
            String tag = ownerTag(lines, i, col);
            if (tag != null) {
                out.add(new Span(i, closingLine(lines, i, tag)));
            }
        }
        return out;
    }

    /**
     * 携带该属性的开标签名：取属性位置之前最近的一个开标签；本行没有则往前找
     * （开标签常跨行写，属性落在第二行）。
     */
    private String ownerTag(List<String> lines, int line, int col) {
        String tag = lastOpenTag(lines.get(line).substring(0, Math.max(col, 0)));
        for (int j = line - 1; tag == null && j >= 0; j--) {
            tag = lastOpenTag(lines.get(j));
        }
        return tag;
    }

    private String lastOpenTag(String segment) {
        Matcher m = OPEN_TAG.matcher(segment);
        String last = null;
        while (m.find()) {
            last = m.group(1);
        }
        return last;
    }

    /** 同名标签深度配对求闭合行；找不到闭合（模板写坏）则退到文件末尾。 */
    private int closingLine(List<String> lines, int start, String tag) {
        Pattern open = Pattern.compile("<" + Pattern.quote(tag) + "\\b");
        Pattern close = Pattern.compile("</" + Pattern.quote(tag) + "\\s*>");
        int depth = 0;
        for (int j = start; j < lines.size(); j++) {
            depth += count(open, lines.get(j));
            depth -= count(close, lines.get(j));
            if (depth <= 0) {
                return j;
            }
        }
        return lines.size() - 1;
    }

    private int count(Pattern p, String s) {
        Matcher m = p.matcher(s);
        int n = 0;
        while (m.find()) {
            n++;
        }
        return n;
    }

    private List<Path> templates() throws IOException {
        try (Stream<Path> files = Files.walk(DIR)) {
            return files.filter(p -> p.toString().endsWith(".html")).sorted().toList();
        }
    }

    private String fileName(Path p) {
        return p.getFileName().toString();
    }
}
