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
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * L0：本版退役页面的**清理确认**（V1.3.0 Story 11.3 · AC1 / AC3）。
 *
 * <h2>为什么要有这一条</h2>
 * 「整页退役、内容并进抽屉」这件事有三个半成品状态，肉眼都看不出来：
 * <ol>
 *   <li>模板删了、Controller 的 {@code @GetMapping} 还在 —— 访问旧地址 500（模板不存在）；</li>
 *   <li>两边都删了、**别的模板里还留着 {@code th:href} 指向它** —— 页面上一个死链，
 *       点下去 404，而运营会以为是权限问题；</li>
 *   <li>都删干净了、**侧导航里那一项还在** —— 最难受的一种：菜单看得见、点了打不开。</li>
 * </ol>
 * 三种都不会让任何既有测试变红（渲染冒烟只跑活着的路由，端点测试只测活着的端点）。
 *
 * <p>纯文件扫描，无 Spring / 无 DB。运行期的「旧地址返 404 而不是 302/200」由
 * {@link com.tailtopia.admin.web.AdminRetiredRoutesTest}（L1）盯。
 */
class AdminRetiredRoutesStaticTest {

    private static final Path TPL = Path.of("src", "main", "resources", "templates", "admin");
    private static final Path SRC = Path.of("src", "main", "java");

    /**
     * 本版退役的整页模板（Story 11.3 AC1 的表 + Epic 10 陆续补进来的商城页）。
     *
     * <p>{@code shop-return-detail} 于 Story 10.1 落地时加入（五区并进 A7 工作台右栏）；
     * {@code shop-order-detail} 于 Story 10.2 加入（五区并进 B15 详情抽屉）。
     *
     * <p>🔴 注意这两条<b>只进这份模板名单，不进 {@link #RETIRED_PARAM_HREFS}</b>：
     * Story 10.1 / 10.2 都明确「不许新开 {@code /{token}/detail}（或 {@code /drawer}）端点」，
     * 于是右栏 / 抽屉片段<b>复用了原来那条整页 mapping</b>（htmx 请求返片段、直达 404）。
     * 把 {@code /admin/shop/returns/}、{@code /admin/shop/orders/} 写进带参残留名单的话，
     * 队列行与抽屉表单里那些合法的 {@code hx-get} / {@code hx-post} 会被判成死链 ——
     * 报的是自己刚建的正路。「直达返 404」由两页各自的 IT 在 L1 钉住。
     */
    private static final List<String> RETIRED_TEMPLATES = List.of(
            "content-detail", "user-detail", "consult-order-detail", "ai-order-detail",
            "anomaly-detail", "refund-detail", "support-ticket-detail",
            "vet-edit", "vet-online", "vet-qualification", "vet-ratings",
            "ratings", "reports", "content-schedules",
            "shop-return-detail", "shop-order-detail");

    /**
     * 退役 GET 路由在模板里的**跳转字面量**。
     *
     * <p>只挑那些「一眼能看出是跳转到整页」的写法：`@{/admin/xxx}` 与拼串。
     * 抽屉深链一律是 {@code ?open=<id>}（D-23），所以下面这些出现在 {@code th:href} 里就是残留。
     */
    private static final Map<String, String> RETIRED_HREFS = new LinkedHashMap<>();

    /**
     * 退役的**带参**路由前缀（AC3 第二条逐字点名的那几种）。
     *
     * <p>🔴 只列无参路由是不够的：`th:href="@{'/admin/content/' + ${p.id}}"`、
     * `@{/admin/users/{id}(id=${u.id})}`、`@{/admin/vets/{id}/edit(id=${v.id})}`
     * 都是本仓库真实在用的写法，也正是 AC3 点名的三种 —— 死链回到模板里，
     * 只匹配无参路由的断言一声不吭（复审 C2，三种形态实测全绿）。
     *
     * <p>⚠️ 后继字符必须限定，否则会误伤**仍在服役**的同前缀路由：
     * `/admin/users/{id}/drawer`、`/admin/content/{postId}/drawer` 都还活着。
     */
    private static final Map<String, String> RETIRED_PARAM_HREFS = new LinkedHashMap<>();

    static {
        RETIRED_HREFS.put("/admin/vets/online", "9.1a：在线态并入兽医列表与抽屉");
        RETIRED_HREFS.put("/admin/ratings", "9.1b：评分并入兽医列表筛选栏与抽屉评分页签");
        RETIRED_HREFS.put("/admin/reports", "2.4：并入统一复核工作台");
        RETIRED_HREFS.put("/admin/content-schedules(", "7.5：并入批量内容的排期页签");
        RETIRED_HREFS.put("/admin/tickets/detail", "2.5：并入被举报用户抽屉");

        RETIRED_PARAM_HREFS.put("/admin/content/", "7.1：内容详情抽屉化（?open=<id>）");
        RETIRED_PARAM_HREFS.put("/admin/users/", "8.1：用户五页签抽屉（?open=<id>）");
        RETIRED_PARAM_HREFS.put("/admin/consult-orders/", "8.4：兽医订单抽屉（?open=<token>）");
        RETIRED_PARAM_HREFS.put("/admin/ai-orders/", "8.4：AI 订单抽屉（?open=<token>）");
        RETIRED_PARAM_HREFS.put("/admin/anomalies/", "2.6：问诊异常抽屉（?open=<id>）");
        RETIRED_PARAM_HREFS.put("/admin/refunds/", "2.8：退款三段流抽屉（?open=<token>）");
        RETIRED_PARAM_HREFS.put("/admin/support-tickets/", "2.7：客服工单抽屉（?open=<token>）");
    }

    private static List<Path> templates() throws IOException {
        try (Stream<Path> s = Files.walk(TPL)) {
            return s.filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".html"))
                    .sorted()
                    .toList();
        }
    }

    /** 去掉 HTML / Thymeleaf 注释：注释里写「原来这里是 xxx.html」是**应该**的，不算残留。 */
    private static String withoutComments(String html) {
        return html.replaceAll("(?s)<!--.*?-->", "");
    }

    @Test
    void everyRetiredPageTemplateIsActuallyGone() throws IOException {
        // 🛡 防空转：这一条用 Files.exists 判「不存在」，工作目录不对时 14 个 exists 全 false，
        //    于是静默通过（另外几条会因 Files.walk 抛异常而暴露）。先钉一个必然存在的锚（复审 P1）。
        assertThat(Files.exists(TPL.resolve("vets.html")))
                .as("连 vets.html 都找不到 —— 工作目录不对，这条断言此刻毫无意义").isTrue();
        List<String> stillThere = new ArrayList<>();
        for (String name : RETIRED_TEMPLATES) {
            if (Files.exists(TPL.resolve(name + ".html"))) {
                stillThere.add(name + ".html");
            }
        }
        assertThat(stillThere)
                .as("这些整页模板在本版已退役（Story 11.3 AC1），内容并进了抽屉 / 页签")
                .isEmpty();
    }

    /**
     * Controller 里不能再 {@code return "admin/<退役模板>"}。
     *
     * <p>🔴 这是「模板删了、返回值没删」那种半成品的唯一机器证据：编译期不报错
     * （视图名是字符串），运行期才 500，而**只有真的访问那条旧地址才会触发**。
     */
    @Test
    void noControllerStillReturnsARetiredTemplate() throws IOException {
        List<String> hits = new ArrayList<>();
        try (Stream<Path> s = Files.walk(SRC)) {
            for (Path p : s.filter(Files::isRegularFile)
                    .filter(f -> f.toString().endsWith(".java")).sorted().toList()) {
                String src = Files.readString(p, StandardCharsets.UTF_8)
                        .replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("(?m)//.*$", "");
                for (String name : RETIRED_TEMPLATES) {
                    if (src.contains("\"admin/" + name + "\"")) {
                        hits.add(p.getFileName() + " → admin/" + name);
                    }
                }
                // 🔴 `return "redirect:/admin/ratings"` 也是「还活着」：模板删了、Controller 删了，
                //    只要还有人往旧地址跳，那条 302 就把旧地址永久续上了（D-23 明确不做旧地址跳转）。
                for (String route : RETIRED_HREFS.keySet()) {
                    if (linksTo(src, route)) {
                        hits.add(p.getFileName() + " → redirect " + route);
                    }
                }
            }
        }
        assertThat(hits).as("退役模板不能再被任何 Controller 作为视图名返回").isEmpty();
    }

    /**
     * 模板里不能再有指向退役整页的 {@code th:href}。
     *
     * <p>跨页跳转一律改成页内深链 {@code ?open=<id>}（D-23）。
     */
    @Test
    void noTemplateStillLinksToARetiredPage() throws IOException {
        List<String> hits = new ArrayList<>();
        for (Path p : templates()) {
            String html = withoutComments(Files.readString(p, StandardCharsets.UTF_8));
            for (Map.Entry<String, String> e : RETIRED_HREFS.entrySet()) {
                if (linksTo(html, e.getKey())) {
                    hits.add(p.getFileName() + " → " + e.getKey() + "（" + e.getValue() + "）");
                }
            }
            for (Map.Entry<String, String> e : RETIRED_PARAM_HREFS.entrySet()) {
                if (linksToParamRoute(html, e.getKey())) {
                    hits.add(p.getFileName() + " → " + e.getKey() + "<id>（" + e.getValue() + "）");
                }
            }
        }
        assertThat(hits).as("退役整页不能再有入口链接；跨页跳转改 ?open=<id> 页内深链（D-23）").isEmpty();
    }

    /**
     * 一条退役路由的**各种写法**都要认得。
     *
     * <p>只对 {@code th:href="@{...}"} 一种写法做字符串匹配是不够的：
     * 换成单引号、换成 {@code hx-get}、写成 Java 里的 {@code redirect:}，
     * 断言就静默放过 —— 而那三种在本仓库都是常见写法。
     * 这里按「属性名（可带 th:/hx- 前缀）+ 任意引号 + 可选 @{ + 路由」匹配，
     * 外加 Java 侧的 {@code redirect:}；路由后面只允许跟 {@code )}、{@code "}、{@code '}、
     * {@code (}（Thymeleaf 带参写法）或结束，避免 {@code /admin/ratings} 误命中
     * {@code /admin/ratings-foo} 这类前缀重合的新路由。
     */
    private static boolean linksTo(String text, String route) {
        String r = Pattern.quote(route.endsWith("(") ? route.substring(0, route.length() - 1) : route);
        // 🔴 `th:attr="hx-get=@{...}"` 是本仓库**用得最多**的 htmx 跳转写法
        //    （th:attr="hx-post=" 99 处 / "hx-get=" 73 处 / "data-drawer-deeplink=" 17 处），
        //    而它的属性名后面跟的是 `@{` 不是引号 —— 第一版正则要求「属性名后紧跟引号」，
        //    恰好把用得最多的那一种漏了（复审 C3）。
        Pattern attr = Pattern.compile(
                "(?:th:attr\\s*=\\s*\"[a-z-]+=|(?:th:)?(?:href|hx-get|hx-post|data-drawer-url"
                        + "|data-drawer-deeplink|action)\\s*=\\s*[\"']\\s*)(?:@\\{)?" + r
                        // ⚠️ `}` 必须在后继字符集里：`th:href="@{/admin/ratings}"` 是最常见的写法，
                        //    漏了它这条断言对**绝大多数真实残留**都不响（实测两种写法全漏）。
                        + "(?:[)}\"'(?]|\\s|$)");
        Pattern redirect = Pattern.compile("redirect:" + r + "(?:[\"'?]|$)");
        return attr.matcher(text).find() || redirect.matcher(text).find();
    }

    /**
     * 带参退役路由：前缀命中且后继不是「仍在服役」的子路径。
     *
     * <p>覆盖三种写法：`@{'/admin/content/' + ${x}}`、`@{/admin/users/{id}(...)}`、
     * `href="/admin/content/3"`。
     */
    private static boolean linksToParamRoute(String text, String prefix) {
        // 🔴 判据是「参数之后 URL 就结束了」，而不是一份「仍在服役的子路径」白名单。
        //    白名单那种写法必须穷举 /ban、/deactivate、/note、/drawer… 几十个在役子路由，
        //    漏一个就是一次假阳性（第一版实测误报 6 处：drawer-users、drawer-content 等
        //    引用的全是 /admin/users/{id}/xxx 这类活着的处置端点）。
        //    退役的是**裸详情页**，所以只有「前缀 + id + URL 结束」才算残留。
        Pattern p = Pattern.compile(
                "(?:th:attr\\s*=\\s*\"[a-z-]+=|(?:th:)?(?:href|hx-get|hx-post|data-drawer-url"
                        + "|data-drawer-deeplink|action)\\s*=\\s*[\"']\\s*)(?:@\\{)?'?"
                        + Pattern.quote(prefix)
                        // id 的三种形态：{id} / ' + ${x} 拼串 / 字面数字；随后必须是 URL 结束
                        + "(?:\\{[^}/]*\\}|'\\s*\\+\\s*\\$\\{[^}]*\\}|\\d+)\\s*(?:[(){}\"'?]|$)");
        return p.matcher(text).find();
    }

    /**
     * 🔴 **路由复活**：Controller 里不该再出现指向退役路由的 {@code @GetMapping}。
     *
     * <p>前面三条都管不了这件事 —— 模板文件删了、视图名不是退役模板名（返回一个片段就行），
     * 一条 {@code @GetMapping("/admin/reports")} 加回来，四条静态断言**全绿**（复审 C4 实测）。
     * 而唯一能管的 AC2 是 L1，云端跑不了。所以补这一条纯文本扫描，代价近乎为零。
     */
    @Test
    void noControllerReintroducesAGetMappingForARetiredRoute() throws IOException {
        List<String> hits = new ArrayList<>();
        List<String> routes = new ArrayList<>(RETIRED_HREFS.keySet());
        routes.replaceAll(x -> x.endsWith("(") ? x.substring(0, x.length() - 1) : x);
        try (Stream<Path> s = Files.walk(SRC)) {
            for (Path p : s.filter(Files::isRegularFile)
                    .filter(f -> f.toString().endsWith(".java")).sorted().toList()) {
                String src = Files.readString(p, StandardCharsets.UTF_8)
                        .replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("(?m)//.*$", "");
                for (String route : routes) {
                    // 注解可能写成全限定名（@org.springframework...GetMapping），也可能带 value = / 数组，
                    // 所以只要求「Get/RequestMapping 的括号里出现这个路径字面量」。
                    if (Pattern.compile("@(?:[\\w.]*\\.)?(?:Get|Request)Mapping\\s*\\([^)]*\""
                            + Pattern.quote(route) + "\"").matcher(src).find()) {
                        hits.add(p.getFileName() + " → @GetMapping(\"" + route + "\")");
                    }
                }
            }
        }
        assertThat(hits)
                .as("退役路由的 GET 映射又回来了 —— 模板删了不等于路由删了，"
                        + "返回一个片段视图名照样能让整页复活")
                .isEmpty();
    }

    /**
     * 侧导航里不能再有退役页的菜单项（AC3 第三条）。
     *
     * <p>🔴 这是三种半成品里最难受的一种：菜单看得见、点了打不开。
     * 导航由 {@code AdminPageCatalog} 驱动，所以这里连 catalog 一起查 ——
     * 只查模板的话，catalog 里留着一条死页面同样会渲染出菜单项。
     */
    @Test
    void navigationHasNoEntryPointingAtARetiredPage() throws IOException {
        String nav = "";
        for (Path p : List.of(TPL.resolve("layout.html"), TPL.resolve("fragments").resolve("nav.html"))) {
            if (Files.exists(p)) {
                nav += withoutComments(Files.readString(p, StandardCharsets.UTF_8));
            }
        }
        Path catalog = SRC.resolve(Path.of("com", "tailtopia", "admin", "shared", "AdminPageCatalog.java"));
        String cat = Files.exists(catalog)
                ? Files.readString(catalog, StandardCharsets.UTF_8).replaceAll("(?s)/\\*.*?\\*/", "")
                        .replaceAll("(?m)//.*$", "")
                : "";

        List<String> hits = new ArrayList<>();
        for (String route : List.of("/admin/vets/online", "/admin/ratings", "/admin/reports",
                "/admin/content-schedules")) {
            if (nav.contains(route)) {
                hits.add("导航模板仍有 " + route);
            }
            if (cat.contains("\"" + route + "\"")) {
                hits.add("AdminPageCatalog 仍有 route=" + route);
            }
        }
        assertThat(hits).as("退役页不该还在侧导航 / 页面目录里 —— 菜单看得见、点了打不开是最糟的半成品").isEmpty();
    }
}
