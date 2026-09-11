package com.tailtopia.admin.shop.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * L0：C2 / C3 / C4 三张报表<b>只读且三帧独立</b>（V1.3.0 Story 10.5 · AC4）。纯文件扫描，无 Spring / 无 DB。
 *
 * <h2>为什么「只读」值得一道 L0 守门</h2>
 * 报表页的数<b>全部是聚合出来的</b> —— 没有一个字段是可以就地改的。
 * 一旦这三页里长出一个写入口（哪怕只是「标记已核对」这种看起来无害的），
 * 运营就会把整页当成可以在这里改数的地方，而改动落到哪张表、与聚合口径怎么对上，
 * 没有任何人回答得了。L1 用例只能在真起了容器时才跑得到，这条在云端就能挡住。
 */
class AdminShopReportsReadOnlyGuardTest {

    private static final Path TEMPLATES = Path.of("src", "main", "resources", "templates", "admin");
    private static final Path CONTROLLERS =
            Path.of("src", "main", "java", "com", "tailtopia", "admin", "shop", "web");

    /** 三帧各自一个模板 + 一个卡区片段（AC4「不得合成一页多分区」）。 */
    private static final List<String> PAGES = List.of(
            "shop-repurchase-dashboard.html", "shop-margin.html", "shop-inventory-turnover.html");
    private static final List<String> FRAGMENTS = List.of(
            "fragments/cards-shop-repurchase.html", "fragments/cards-shop-margin.html",
            "fragments/cards-shop-turnover.html");

    private static String read(Path p) throws IOException {
        return Files.readString(p, StandardCharsets.UTF_8);
    }

    /** 剥注释再判：注释里写的 `hx-post` / `POST` 不该被算成写入口。 */
    private static String stripped(String s) {
        return s.replaceAll("(?s)<!--.*?-->", "").replaceAll("(?s)/\\*.*?\\*/", "")
                .replaceAll("(?m)//.*$", "");
    }

    @Test
    @DisplayName("🔴 三张报表页与它们的卡区片段里没有任何写入口")
    void noReportTemplateContainsAWriteEntryPoint() throws IOException {
        for (String f : java.util.stream.Stream.concat(PAGES.stream(), FRAGMENTS.stream()).toList()) {
            String html = stripped(read(TEMPLATES.resolve(f)));
            assertThat(html).as(f + " 里出现了 hx-post —— 只读报表页不该有写入口")
                    .doesNotContain("hx-post");
            assertThat(html).as(f + " 里出现了 POST 表单 —— 期间 / 窗口切换是**读参数**变化，只能是 method=\"get\"")
                    .doesNotContain("method=\"post\"");
            assertThat(html).as(f + " 里出现了配置卡 —— 那是模板 D 的写入形态，不属于只读报表")
                    .doesNotContain("data-config-card");
        }
    }

    /**
     * 🔴 <b>三条路由只能有 GET</b>（AC4「无写端点、无导出，现状无、不新增」）。
     *
     * <p>模板里没有写入口只说明「界面上点不到」。真正的门是<b>服务端有没有这条路由</b>：
     * 一个没有 UI 的 POST 端点照样可以被直接调用，而且它不会出现在任何页面截图里
     * ——Story 10.3 就在 banner 上发现过一条「有端点没 UI」的死路由。
     */
    @Test
    @DisplayName("🔴 三条报表路由在 Controller 里只有 @GetMapping，没有任何 POST / 导出端点")
    void theThreeReportRoutesHaveNoWriteOrExportEndpoint() throws IOException {
        String repurchase = stripped(read(CONTROLLERS.resolve("AdminRepurchaseDashboardController.java")));
        String finance = stripped(read(CONTROLLERS.resolve("AdminShopFinanceController.java")));

        // 🔴 判据是 `PostMapping` 而**不是** `@PostMapping`：写成后者的话，
        //    一个 `@org.springframework.web.bind.annotation.PostMapping(...)` 的全限定注解就能绕过去
        //    —— 落地时实测过这一条，带 @ 的断言对全限定写法完全无感。
        //    `RequestMethod.POST` 是 `@RequestMapping(method = …)` 那条同样合法的路径。
        for (String s : List.of(repurchase, finance)) {
            assertThat(s).as("报表 Controller 里出现了 POST 端点 —— 只读报表不该有写入口，"
                            + "而没有 UI 的端点照样能被直接调用，且不会出现在任何页面截图里")
                    .doesNotContain("PostMapping").doesNotContain("RequestMethod.POST");
        }
        // 导出端点也是 GET，所以单看 @GetMapping 数量不够 —— 直接钉路径里不能有 export / csv / xlsx
        for (String s : List.of(repurchase, finance)) {
            assertThat(s).as("报表 Controller 里出现了导出端点（AC4 明确不新增）")
                    .doesNotContain("export").doesNotContain(".csv").doesNotContain(".xlsx");
        }
    }

    /**
     * AC4：三页<b>各自一个模板</b>，且都套模板 C 的壳（只读标识由壳给出）。
     *
     * <p>合成一页多分区的话，三页各不相同的权限码（C2 走 {@code config.view}/{@code order.view}，
     * C3/C4 走 {@code shop.finance_view}）就没法分别把门 —— 要么把成本数字漏给运营，
     * 要么把复购看板锁给财务。这不是排版偏好，是权限边界。
     */
    @Test
    @DisplayName("AC4：三帧独立模板，且都套 tpl-c-report（只读标识由壳给出）")
    void eachReportIsItsOwnFrameOnTemplateC() throws IOException {
        for (String f : PAGES) {
            String html = read(TEMPLATES.resolve(f));
            assertThat(html).as(f + " 没有套模板 C 的壳 —— data-readonly 只读标识就没了")
                    .contains("tpl-c-report :: report");
        }
    }
}
