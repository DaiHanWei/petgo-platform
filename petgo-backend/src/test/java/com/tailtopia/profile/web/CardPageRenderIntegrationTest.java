package com.tailtopia.profile.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tailtopia.auth.domain.User;
import com.tailtopia.profile.domain.PetProfile;
import com.tailtopia.profile.repository.PetProfileRepository;
import com.tailtopia.support.ApiIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

/**
 * L1：H5 名片页<b>整页渲染</b>（V1.1.6 Story 1.2）。真跑 Thymeleaf，拿到最终 HTML 再断言。
 *
 * <p>为什么必须有这一层：Story 1.2 <b>整页重写</b>了 {@code card.html}。
 * 控制器的 L0 测试只能验「下发了什么数据」，验不了「模板把它渲染成了什么」。
 * 本类专盯三件在整页重写中最容易出事、且出事后果最重的事：
 * <ol>
 *   <li><b>页面出现中文</b>（AC3）—— 面向印尼用户的对外页，漏一处就是穿帮</li>
 *   <li><b>OG / Twitter 标签丢失</b> —— 分享到 WhatsApp 没有预览图，这页的核心用途就废了</li>
 *   <li><b>noindex 丢失</b> —— 私人宠物名片被搜索引擎收录</li>
 * </ol>
 * 后两条在重写模板时极易被连带删除，且<b>删了页面照样正常显示</b>，没有测试就发现不了。
 */
class CardPageRenderIntegrationTest extends ApiIntegrationTest {

    @Autowired
    private PetProfileRepository profiles;

    private String createProfileAndGetToken(User owner) throws Exception {
        mvc.perform(post("/api/v1/pet-profiles")
                        .header(HttpHeaders.AUTHORIZATION, userBearer(owner.getId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"petType":"CAT","name":"Momo","breed":"Kucing Domestik",
                                 "birthday":"2024-03-10","intro":"Suka tidur"}
                                """))
                .andExpect(status().isCreated());
        return profiles.findByOwnerId(owner.getId()).orElseThrow().getCardToken();
    }

    private String render(String token) throws Exception {
        return mvc.perform(get("/p/" + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    // ===== AC3：整页不得出现中文 =====

    /**
     * 🛡 渲染出来的 HTML 里，<b>除用户自填内容外</b>，一个中日韩字符都不能有。
     *
     * <p>这条是 AC3 的兜底 —— 比逐个字段检查省事得多，能抓到任何来源的中文：
     * 模板里手写的、后端下发的（比如误用了里程碑的 {@code titleZh}）、异常兜底文案里的。
     * 它在本 story 里真的抓到了两处：HTML 注释（普通 {@code <!-- -->} 会原样发给用户，
     * 已全部改成 Thymeleaf 解析器级注释 {@code <!--/* * /-->}）与 CSS/JS 里的中文注释。
     *
     * <p>⚠️ <b>为什么要排除用户自填内容</b>：宠物名、自述、主人昵称是<b>用户数据</b>，
     * 用户完全可以起中文名（印尼也有华人用户），那不是 i18n 问题、也不该被这条拦住。
     * AC3 管的是<b>后端硬编码文案</b>。这里把昵称从 HTML 里剔掉再检查，正是这个意思。
     * （测试夹具 {@code newUser()} 造的昵称恰好形如「用户123」，所以必须剔除，否则永远红。）
     */
    @Test
    void renderedPageContainsNoHardcodedChinese() throws Exception {
        User owner = newUser();
        String html = render(createProfileAndGetToken(owner));

        // 剔除用户自填内容（昵称）后再检查剩余部分
        String nickname = owner.getNickname();
        String stripped = nickname == null ? html : html.replace(nickname, "");

        StringBuilder found = new StringBuilder();
        stripped.codePoints()
                .filter(cp -> (cp >= 0x4E00 && cp <= 0x9FFF) || (cp >= 0x3400 && cp <= 0x4DBF))
                .limit(40)
                .forEach(found::appendCodePoint);

        assertThat(found.toString())
                .as("H5 名片页出现了后端硬编码的中文（AC3 禁止）。片段：%s"
                        + "（注意：HTML 注释也会发给用户，中文注释请写成 Thymeleaf 解析器级注释）", found)
                .isEmpty();
    }

    // ===== 整页重写最易连带删除的三样 =====

    /** 🛡 noindex 丢了 → 私人宠物名片被搜索引擎收录。 */
    @Test
    void noindexMetaSurvivesTheRewrite() throws Exception {
        assertThat(render(createProfileAndGetToken(newUser())))
                .as("noindex meta 丢失 —— 名片会被搜索引擎收录")
                .contains("name=\"robots\"")
                .contains("noindex");
    }

    /** 🛡 OG / Twitter 整组丢了 → 分享到 WhatsApp / Line 没有预览图，这页的核心用途就废了。 */
    @Test
    void openGraphAndTwitterTagsSurviveTheRewrite() throws Exception {
        String html = render(createProfileAndGetToken(newUser()));
        assertThat(html)
                .contains("property=\"og:title\"")
                .contains("property=\"og:description\"")
                .contains("property=\"og:image\"")
                .contains("property=\"og:url\"")
                .contains("name=\"twitter:card\"")
                .contains("name=\"twitter:image\"");
        // OG 标题仍是印尼语（修 20260702-208 定的口径）
        assertThat(html).contains("Kisah tumbuh kembang");
    }

    /**
     * 🛡 三级降级链的 JS 丢了 → 已装 App 的用户点 CTA 不会直接打开 App，全部落到商店页。
     * 它是「已装就直接开」的唯一实现。
     */
    @Test
    void deepLinkFallbackScriptSurvivesTheRewrite() throws Exception {
        String html = render(createProfileAndGetToken(newUser()));
        assertThat(html)
                .contains("market://details")     // Android 第二级
                .contains("data-deeplink")        // 深链数据位
                .contains("pagehide");            // 上一级成功即取消后续跳转
        assertThat(html).contains("tailtopia://card/");
    }

    // ===== AC5 / AC6：单 CTA 与字体 =====

    /** 双 CTA 已合并为单个（原两个按钮行为完全相同）。 */
    @Test
    void hasExactlyOneCta() throws Exception {
        String html = render(createProfileAndGetToken(newUser()));
        assertThat(html).contains("id=\"ctaPrimary\"");
        assertThat(html)
                .as("secondary CTA 应已删除（双 CTA 合并为单个）")
                .doesNotContain("ctaSecondary");
        assertThat(html).contains("Lihat cerita Momo");
    }

    /** 字体照抄视觉稿：两个 preconnect + display=swap，且不得自行拼装 URL。 */
    @Test
    void fontHeadMatchesTheDesignSource() throws Exception {
        String html = render(createProfileAndGetToken(newUser()));
        assertThat(html)
                .contains("rel=\"preconnect\" href=\"https://fonts.googleapis.com\"")
                .contains("rel=\"preconnect\" href=\"https://fonts.gstatic.com\" crossorigin")
                .contains("display=swap");
        // 四个字体各司其职，缺一个剪贴簿风格就塌一块
        assertThat(html).contains("Archivo").contains("Poppins").contains("Caveat").contains("JetBrains+Mono");
    }

    // ===== AC1 / AC2 / AC7：数据与降级 =====

    /** 元信息行渲染出来了，且没有连续分隔符。 */
    @Test
    void metaLineRendersWithoutEmptySegments() throws Exception {
        User owner = newUser();
        String token = createProfileAndGetToken(owner);
        // 补上性别（Story 1.1）
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .patch("/api/v1/pet-profiles/me")
                        .header(HttpHeaders.AUTHORIZATION, userBearer(owner.getId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"sex":"FEMALE"}
                                """))
                .andExpect(status().isOk());

        String html = render(token);
        assertThat(html).contains("Betina");
        assertThat(html).doesNotContain(" ·  · ").doesNotContain("··");
    }

    /**
     * AC2：总数按物种取常量目录（猫 <b>31</b>），<b>不是</b>视觉稿里那个示意的 30。
     *
     * <p>⚠️ <b>刚建的档案不是「零里程碑」</b>：建档动作本身会自动完成 C-S1
     * （{@code Profil dibuat}，SYSTEM_AUTO），所以新档案一进来就是 <b>1 / 31</b>。
     * 换句话说 <b>E2 零态屏在真实系统里几乎不会自然出现</b> —— 只有存量老档案（roster 尚未物化）
     * 或数据异常才会是 0。零态本身由 L0 的 {@code CardPageControllerTest} 覆盖。
     */
    @Test
    void freshProfileShowsOneCompletedAndCatalogTotal() throws Exception {
        String html = render(createProfileAndGetToken(newUser()));

        assertThat(html)
                .as("里程碑总数应取常量目录的 31（猫），不是视觉稿示意的 30")
                .contains("1 / 31");
        // 建档那条里程碑的印尼语标题（不是中文，也不是 code）
        assertThat(html).contains("Profil dibuat");
        // 有里程碑 → 票根第三列「Tonggak」出现
        assertThat(html).contains("Tonggak");
        // 无快乐时刻 → 照片占位与手写小字（E2「刚开本子」，不是把模块藏掉）
        assertThat(html).contains("Unduh App untuk lihat").contains("baru mulai!");
    }

    /** 存量档案没有生日 / 性别 / 品种也能正常渲染（AC3 的容错半条）。 */
    @Test
    void profileWithMinimalDataStillRenders() throws Exception {
        User owner = newUser();
        mvc.perform(post("/api/v1/pet-profiles")
                        .header(HttpHeaders.AUTHORIZATION, userBearer(owner.getId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"petType":"OTHER","name":"X","birthday":"2020-01-01"}
                                """))
                .andExpect(status().isCreated());
        PetProfile p = profiles.findByOwnerId(owner.getId()).orElseThrow();

        String html = render(p.getCardToken());
        assertThat(html).contains("Lihat cerita X");
        assertThat(html).doesNotContain("··");
        // 通用物种总数是 16；建档自动完成 1 条 → 1 / 16
        assertThat(html).contains("1 / 16");
    }
}
