package com.tailtopia.shared.error;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.tailtopia.support.ApiIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

/**
 * L1：H5 错误落点（V1.1.6 Story 1.3 · AC3/AC4）。
 *
 * <p>本类真正要守的是 <b>AC4 的三条红线</b>。加一个错误页看起来无害，
 * 但它极容易顺手把别人的错误响应也一起改掉：
 * <ol>
 *   <li><b>API 出错必须仍回 JSON</b> —— 客户端拿到一张 HTML 页只会解析失败</li>
 *   <li><b>运营后台不受影响</b> —— 后台报错不该显示宠物剪贴簿页</li>
 *   <li><b>状态码不变</b> —— 渲染了页面不代表请求成功，改成 200 会让监控与爬虫误判</li>
 * </ol>
 * 这三条如果破了，<b>页面本身看起来还是好的</b>，只有测试能发现。
 */
@org.springframework.context.annotation.Import(H5BoomTestController.class)
class H5ErrorControllerIntegrationTest extends ApiIntegrationTest {

    // ===== AC3：H5 链路出错 → 剪贴簿错误页 =====

    /**
     * 🛡 <b>H5 路径上真正的 5xx</b> → 剪贴簿加载失败页（带重试），而不是一坨 JSON。
     *
     * <p>这是 AC3 的核心。触发方式：让页面控制器依赖的下游抛异常。
     * 这里用一个**格式合法但会让渲染链路炸掉**的场景 —— 见 {@code BoomController}（仅测试用）。
     */
    @Test
    void h5ServerErrorRendersRetryPage() throws Exception {
        var res = mvc.perform(get("/p/boom/please").accept(MediaType.TEXT_HTML))
                .andReturn().getResponse();

        assertThat(res.getContentAsString())
                .as("H5 路径上的 5xx 应渲染带重试的加载失败页")
                .contains("Gagal memuat")
                .contains("Periksa koneksi internetmu")
                .contains("Coba lagi");
        // 🛡 状态码原样透传，不因为渲染了页面就变 200
        assertThat(res.getStatus())
                .as("状态码不得被改成 200")
                .isEqualTo(500);
    }

    /** 里程碑分享那条链路同样适用（两条 H5 路径都在分流名单里）。 */
    @Test
    void milestonePathServerErrorAlsoRendersRetryPage() throws Exception {
        assertThat(mvc.perform(get("/m/boom/please").accept(MediaType.TEXT_HTML))
                        .andReturn().getResponse().getContentAsString())
                .contains("Gagal memuat");
    }

    /**
     * ⚠️ <b>H5 路径上的「找不到」是终局，不该给重试按钮。</b>
     *
     * <p>链接输错 / 路径不存在 → 走失效页（E3），而不是那张写着「再试一次」的加载失败页（E4）。
     * 给一个「找不到」配重试按钮，只会让人白点。
     */
    @Test
    void h5NotFoundGoesToGonePageNotRetryPage() throws Exception {
        var res = mvc.perform(get("/p/deep/not/a/route").accept(MediaType.TEXT_HTML))
                .andReturn().getResponse();

        assertThat(res.getStatus()).isEqualTo(404);
        assertThat(res.getContentAsString())
                .contains("Halaman ini")
                .doesNotContain("Coba lagi");
    }

    /** 错误页也要 noindex（它同样会被抓取器碰到）。 */
    @Test
    void errorPageIsNoindex() throws Exception {
        assertThat(mvc.perform(get("/p/boom/please").accept(MediaType.TEXT_HTML))
                        .andReturn().getResponse().getContentAsString())
                .contains("noindex");
    }

    /** 🛡 错误页整页无中文。 */
    @Test
    void errorPageHasNoChinese() throws Exception {
        String html = mvc.perform(get("/p/boom/please").accept(MediaType.TEXT_HTML))
                .andReturn().getResponse().getContentAsString();

        StringBuilder found = new StringBuilder();
        html.codePoints()
                .filter(cp -> (cp >= 0x4E00 && cp <= 0x9FFF) || (cp >= 0x3400 && cp <= 0x4DBF))
                .limit(40)
                .forEach(found::appendCodePoint);
        assertThat(found.toString())
                .as("加载失败页出现了中文。片段：%s", found)
                .isEmpty();
    }

    // ===== 🛡 AC4 红线一：API 出错仍回 JSON =====

    /**
     * 🛡 <b>API 客户端出错必须继续拿到 JSON</b>，绝不能收到一张 HTML 页。
     *
     * <p>这是加错误页时最容易破坏的一条：给 {@code templates/} 放个 {@code error.html}
     * 就会把所有错误响应都变成 HTML。
     */
    @Test
    void apiErrorsStillReturnJsonNotHtml() throws Exception {
        var res = mvc.perform(get("/api/v1/definitely-no-such-endpoint")
                        .accept(MediaType.APPLICATION_JSON))
                .andReturn().getResponse();

        String body = res.getContentAsString();
        assertThat(body)
                .as("API 出错不得返回 HTML 页面")
                .doesNotContain("<!DOCTYPE html>")
                .doesNotContain("Gagal memuat");
        if (!body.isBlank()) {
            assertThat(body.trim()).startsWith("{");
        }
    }

    /** 即使路径在 {@code /p/} 下，只要客户端要的是 JSON，就不能塞 HTML 给它。 */
    @Test
    void h5PathWithJsonAcceptDoesNotGetHtml() throws Exception {
        String body = mvc.perform(get("/p/boom/please").accept(MediaType.APPLICATION_JSON))
                .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain("Gagal memuat").doesNotContain("<!DOCTYPE html>");
    }

    // ===== 🛡 AC4 红线二：运营后台不受影响 =====

    /**
     * 🛡 <b>后台报错不该显示宠物剪贴簿页。</b>
     *
     * <p>运营看到一张「belum muncul…」的宠物拍立得，会以为自己点错了地方。
     * 后台有它自己的一套界面语言，错误页必须按受众分流。
     */
    @Test
    void adminErrorsDoNotGetTheScrapbookPage() throws Exception {
        String body = mvc.perform(get("/admin/definitely-no-such-page").accept(MediaType.TEXT_HTML))
                .andReturn().getResponse().getContentAsString();

        assertThat(body)
                .as("运营后台的错误响应被 H5 错误页接管了")
                .doesNotContain("Gagal memuat")
                .doesNotContain("belum muncul");
    }

    /** 其它任意非 H5 路径同样不受影响。 */
    @Test
    void unrelatedPathsDoNotGetTheScrapbookPage() throws Exception {
        for (String path : new String[] {"/no-such-page", "/brand/nope.svg", "/actuator/nope"}) {
            assertThat(mvc.perform(get(path).accept(MediaType.TEXT_HTML))
                            .andReturn().getResponse().getContentAsString())
                    .as("路径 %s 的错误响应不该被 H5 错误页接管", path)
                    .doesNotContain("Gagal memuat");
        }
    }

    /** 正常的 H5 页面不受影响（错误落点没有误伤正常路径）。 */
    @Test
    void healthyH5PagesAreUnaffected() throws Exception {
        // 不存在的 token → 仍是失效页（404），而不是加载失败页
        var res = mvc.perform(get("/p/no-such-token").accept(MediaType.TEXT_HTML))
                .andReturn().getResponse();
        assertThat(res.getStatus()).isEqualTo(404);
        assertThat(res.getContentAsString())
                .as("token 不存在属于「终局」，应走失效页而不是「可重试」的加载失败页")
                .contains("Halaman ini")
                .doesNotContain("Coba lagi");
    }

    /** 未登录访问受保护 API 仍是 401 JSON，没被错误页搅乱。 */
    @Test
    void unauthenticatedApiStillGets401Json() throws Exception {
        var res = mvc.perform(get("/api/v1/pet-profiles/me")
                        .header(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE))
                .andReturn().getResponse();

        assertThat(res.getStatus()).isEqualTo(401);
        assertThat(res.getContentAsString()).doesNotContain("<!DOCTYPE html>");
    }
}
