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
     * 本版退役的整页模板（Story 11.3 AC1 的表，去掉两条商城的）。
     *
     * <p>⚠️ {@code shop-order-detail} / {@code shop-return-detail} **不在这里**：
     * 它们归 Story 10.1 / 10.2，而 **Epic 10 前置是 v1.4.0 电商线合入（AD-12），本轮未执行**。
     * 把它们写进来，这条测试从落地起就是红的 —— 一条从来没绿过的护栏，等于没有。
     * Epic 10 落地时再加进来（届时 Story 11.1 的白名单里已经给了预授权）。
     */
    private static final List<String> RETIRED_TEMPLATES = List.of(
            "content-detail", "user-detail", "consult-order-detail", "ai-order-detail",
            "anomaly-detail", "refund-detail", "support-ticket-detail",
            "vet-edit", "vet-online", "vet-qualification", "vet-ratings",
            "ratings", "reports", "content-schedules");

    /**
     * 退役 GET 路由在模板里的**跳转字面量**。
     *
     * <p>只挑那些「一眼能看出是跳转到整页」的写法：`@{/admin/xxx}` 与拼串。
     * 抽屉深链一律是 {@code ?open=<id>}（D-23），所以下面这些出现在 {@code th:href} 里就是残留。
     */
    private static final Map<String, String> RETIRED_HREFS = new LinkedHashMap<>();

    static {
        RETIRED_HREFS.put("/admin/vets/online", "9.1a：在线态并入兽医列表与抽屉");
        RETIRED_HREFS.put("/admin/ratings", "9.1b：评分并入兽医列表筛选栏与抽屉评分页签");
        RETIRED_HREFS.put("/admin/reports", "2.4：并入统一复核工作台");
        RETIRED_HREFS.put("/admin/content-schedules(", "7.5：并入批量内容的排期页签");
        RETIRED_HREFS.put("/admin/tickets/detail", "2.5：并入被举报用户抽屉");
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
        Pattern attr = Pattern.compile(
                "(?:th:)?(?:href|hx-get|hx-post|data-drawer-url|action)\\s*=\\s*[\"']\\s*(?:@\\{)?" + r
                        // ⚠️ `}` 必须在后继字符集里：`th:href="@{/admin/ratings}"` 是最常见的写法，
                        //    漏了它这条断言对**绝大多数真实残留**都不响（实测两种写法全漏）。
                        + "(?:[)}\"'(?]|\\s|$)");
        Pattern redirect = Pattern.compile("redirect:" + r + "(?:[\"'?]|$)");
        return attr.matcher(text).find() || redirect.matcher(text).find();
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
