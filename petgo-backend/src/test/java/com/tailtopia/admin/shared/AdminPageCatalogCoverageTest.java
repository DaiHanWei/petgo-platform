package com.tailtopia.admin.shared;

import static org.assertj.core.api.Assertions.assertThat;

import com.tailtopia.support.ApiIntegrationTest;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * L1：页面目录 ↔ 真实注册路由 **双向**核对（V1.3.0 Story 11.4 · AC1）。
 *
 * <h2>为什么必须是「双向」</h2>
 * {@link AdminPageCatalog} 是导航与权限矩阵的**唯一数据源**，两个方向漏一边的后果不一样、
 * 但都不会让别的测试变红：
 * <ul>
 *   <li><b>路由有、目录没有</b> → 这一页进不了侧导航，也进不了权限矩阵的行。
 *       页面本身能打开（知道 URL 的人能进），所以渲染冒烟全绿；
 *       但**没有任何角色能被授予它的权限**，等于交付了一个只有超管能用的页面。</li>
 *   <li><b>目录有、路由没有</b> → 侧导航里一个点开 404 的死项。运营会当成权限问题来问。</li>
 * </ul>
 *
 * <h2>为什么用 {@link RequestMappingHandlerMapping} 而不是扫源码</h2>
 * 它拿到的是 Spring **真正注册**的路由：{@code @StagOnly}（= {@code @Profile("stag")}）
 * 的 Controller 在非 stag profile 下根本不注册 —— 扫源码看不出这个差别，会把 stag 专用页
 * 当成漏配的生产页报出来。文本层的近似核对在
 * {@code scripts/ci/check-admin-permission-consistency.sh}（L0，无 Docker 也能跑）。
 *
 * <p>例外清单与那个脚本共用一份 {@code scripts/ci/admin-page-catalog-exceptions.txt} ——
 * 「哪些 GET 不算页面」这件事只能有一个口径，两套各写各的迟早分叉。
 */
class AdminPageCatalogCoverageTest extends ApiIntegrationTest {

    private static final Path EXCEPTIONS = Path.of("..", "scripts", "ci", "admin-page-catalog-exceptions.txt");

    @Autowired
    private RequestMappingHandlerMapping handlerMapping;

    /** 片段 / 导出 / 带路径参数的详情 —— 都不是「页面」。与生成器和脚本同一口径。 */
    private static boolean looksLikeAPage(String path) {
        if (!path.startsWith("/admin")) {
            return false;
        }
        if (path.matches(".*\\{.*")) {
            return false;   // 带路径参数的一律是详情 / 片段
        }
        if (path.matches(".*(export|\\.csv|\\.xlsx|drawer|/detail|/queue|/fragment|/preview"
                + "|/search|/lookup|/suggest|/json)$")) {
            return false;
        }
        return !path.matches("^/admin/(login|logout|denied|lang|oauth|nav)(/.*)?$");
    }

    private static Set<String> exceptions() throws Exception {
        Set<String> out = new TreeSet<>();
        Path p = Files.exists(EXCEPTIONS) ? EXCEPTIONS : Path.of("scripts", "ci", "admin-page-catalog-exceptions.txt");
        for (String line : Files.readAllLines(p, StandardCharsets.UTF_8)) {
            String s = line.replaceAll("#.*$", "").trim();
            if (s.startsWith("/admin")) {
                out.add(s);
            }
        }
        assertThat(out).as("例外清单读不到内容，说明路径不对 —— 别让这条测试因为读空文件而假绿").isNotEmpty();
        return out;
    }

    /** Spring 真正注册的 /admin 页面级 GET 路径。 */
    private Set<String> livePageRoutes() {
        Set<String> routes = new LinkedHashSet<>();
        for (RequestMappingInfo info : handlerMapping.getHandlerMethods().keySet()) {
            boolean isGet = info.getMethodsCondition().getMethods().isEmpty()
                    || info.getMethodsCondition().getMethods().contains(RequestMethod.GET);
            if (!isGet) {
                continue;
            }
            for (String pattern : info.getPatternValues()) {
                if (looksLikeAPage(pattern)) {
                    routes.add(pattern);
                }
            }
        }
        return routes;
    }

    @Test
    void everyLivePageRouteHasExactlyOneCatalogEntry() throws Exception {
        Set<String> live = new TreeSet<>(livePageRoutes());
        live.removeAll(exceptions());

        List<String> catalogRoutes = AdminPageCatalog.PAGES.stream()
                .map(AdminPageCatalog.Page::route).filter(r -> r != null).toList();
        assertThat(new LinkedHashSet<>(catalogRoutes))
                .as("同一条 route 在目录里出现了两次 —— 矩阵会多一行、导航会多一项")
                .hasSameSizeAs(catalogRoutes);

        List<String> missing = new ArrayList<>();
        for (String r : live) {
            if (!catalogRoutes.contains(r)) {
                missing.add(r);
            }
        }
        assertThat(missing)
                .as("这些页面进不了侧导航、也进不了权限矩阵 —— 等于只有超管能用；"
                        + "确实不是页面的话，写进 scripts/ci/admin-page-catalog-exceptions.txt 并说明理由")
                .isEmpty();
    }

    @Test
    void everyCatalogRoutePointsAtALiveRoute() throws Exception {
        Set<String> live = livePageRoutes();
        List<String> dead = new ArrayList<>();
        for (AdminPageCatalog.Page p : AdminPageCatalog.PAGES) {
            if (p.route() != null && !live.contains(p.route())) {
                dead.add(p.key() + " → " + p.route());
            }
        }
        assertThat(dead)
                .as("目录里的 route 没有对应的存活 GET —— 侧导航里一个点开 404 的死项，"
                        + "运营只会当成权限问题来问")
                .isEmpty();
    }
}
