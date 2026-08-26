package com.tailtopia.profile.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tailtopia.auth.domain.User;
import com.tailtopia.auth.repository.UserRepository;
import com.tailtopia.profile.repository.PetProfileRepository;
import com.tailtopia.support.ApiIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

/**
 * L1：H5 失效页（V1.1.6 Story 1.3）。
 *
 * <p>这一页此前是<b>全篇英文</b>，而正常页全篇印尼语 —— 同一条链接、同一个人，换个状态就换种语言。
 * 本类钉住三件事：① 真的印尼语了；② <b>两条链路都生效</b>（该模板被名片与里程碑分享共用）；
 * ③ 防枚举口径没被文案改动带坏。
 */
class CardGonePageIntegrationTest extends ApiIntegrationTest {

    @Autowired
    private PetProfileRepository profiles;

    @Autowired
    private UserRepository users;

    private String createProfileAndGetToken(User owner) throws Exception {
        mvc.perform(post("/api/v1/pet-profiles")
                        .header(HttpHeaders.AUTHORIZATION, userBearer(owner.getId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"petType":"CAT","name":"Momo","birthday":"2024-03-10"}
                                """))
                .andExpect(status().isCreated());
        return profiles.findByOwnerId(owner.getId()).orElseThrow().getCardToken();
    }

    // ===== AC1：印尼语 + E3 文案 =====

    @Test
    void gonePageIsInIndonesianWithFinalCopy() throws Exception {
        String html = mvc.perform(get("/p/no-such-token-at-all"))
                .andExpect(status().isNotFound())
                .andReturn().getResponse().getContentAsString();

        assertThat(html)
                .contains("Halaman ini")
                .contains("sudah tidak ada")
                .contains("Paspor hewan ini sudah dihapus atau tautannya tidak berlaku lagi.")
                .contains("Jelajahi hewan lain");
        // 旧英文一句都不许剩
        assertThat(html)
                .doesNotContain("no longer available")
                .doesNotContain("Discover pets");
        // 页面语言标记要跟内容一致（原来是 lang="en"）
        assertThat(html).contains("<html lang=\"id\"");
    }

    /** 🛡 整页无中文（失效页没有用户自填内容，可以直接全量断言）。 */
    @Test
    void gonePageHasNoChinese() throws Exception {
        String html = mvc.perform(get("/p/no-such-token-at-all"))
                .andReturn().getResponse().getContentAsString();

        StringBuilder found = new StringBuilder();
        html.codePoints()
                .filter(cp -> (cp >= 0x4E00 && cp <= 0x9FFF) || (cp >= 0x3400 && cp <= 0x4DBF))
                .limit(40)
                .forEach(found::appendCodePoint);
        assertThat(found.toString())
                .as("失效页出现了中文。片段：%s（注意 HTML 注释也会发给用户，"
                        + "中文注释请写成 Thymeleaf 解析器级注释）", found)
                .isEmpty();
    }

    /** 🛡 失效页同样不进搜索引擎。 */
    @Test
    void gonePageKeepsNoindex() throws Exception {
        assertThat(mvc.perform(get("/p/no-such-token-at-all"))
                        .andReturn().getResponse().getContentAsString())
                .contains("name=\"robots\"")
                .contains("noindex");
    }

    // ===== AC2：两条链路共用，都要验 =====

    /**
     * 🛡 该模板被<b>宠物名片</b>与<b>里程碑分享</b>两条链路共用，两条都必须生效。
     * 只验一条就发版，另一条可能还停在英文页上。
     */
    @Test
    void bothShareLinksRenderTheSameIndonesianGonePage() throws Exception {
        String cardHtml = mvc.perform(get("/p/no-such-token-at-all"))
                .andExpect(status().isNotFound())
                .andReturn().getResponse().getContentAsString();
        String milestoneHtml = mvc.perform(get("/m/no-such-token-at-all"))
                .andExpect(status().isNotFound())
                .andReturn().getResponse().getContentAsString();

        for (String html : new String[] {cardHtml, milestoneHtml}) {
            assertThat(html).contains("Halaman ini").contains("Jelajahi hewan lain");
            assertThat(html).doesNotContain("no longer available");
        }
    }

    // ===== AC5：防枚举 =====

    /**
     * 🛡 「token 不存在」与「账号已注销」必须<b>同页面、同状态码、同文案</b>。
     *
     * <p>改文案时最容易破坏这条 —— 顺手加一句「该账号已注销」就泄露了 token 曾经存在。
     * E3 的副文用「已删除<b>或</b>链接失效」把两种原因糊在一起，是刻意的。
     *
     * <p>⚠️ 这里用的是<b>注销</b>（{@code deletedAt}），不是<b>停用</b>（{@code status=DEACTIVATED}）。
     * 两者是不同概念，而 {@code AccountQueryService.isActive()} <b>只看注销</b> ——
     * 即被运营封号的账号，其分享页仍可访问。那是另一个议题，见本 story 的 Completion Notes。
     */
    @Test
    void deletedAccountAndUnknownTokenAreIndistinguishable() throws Exception {
        User owner = newUser();
        String token = createProfileAndGetToken(owner);
        // 注销（软删 + PII 匿名化），这才是 isActive 判定的那条路径
        User u = users.findById(owner.getId()).orElseThrow();
        u.anonymizeForDeletion(java.time.Instant.now());
        users.save(u);

        var deleted = mvc.perform(get("/p/" + token)).andReturn().getResponse();
        var unknown = mvc.perform(get("/p/no-such-token-at-all")).andReturn().getResponse();

        assertThat(deleted.getStatus())
                .as("两种情况的状态码必须一致，否则可据此判断 token 是否存在过")
                .isEqualTo(unknown.getStatus());
        assertThat(deleted.getContentAsString())
                .as("两种情况的页面内容必须一致")
                .isEqualTo(unknown.getContentAsString());
    }

    // ===== 封号（停用）账号的分享页同样不可见（2026-08-17 产品拍板） =====

    /**
     * 🛡 <b>被运营封号（停用）的用户，其宠物分享页必须不可见。</b>
     *
     * <p>此前只有<b>注销</b>（{@code deletedAt}）会让分享页失效，<b>停用</b>
     * （{@code status=DEACTIVATED}，即 V1.1.4 Story 3.2 的封号）不会 ——
     * 也就是说被封号的人，他的宠物头像、名字、照片、里程碑照样对全网可见。
     * 2026-08-17 产品拍板：<b>封号与注销的 H5 都不可见</b>。
     */
    @Test
    void suspendedAccountAlsoHidesTheSharePage() throws Exception {
        User owner = newUser();
        String token = createProfileAndGetToken(owner);
        // 先确认正常可见
        mvc.perform(get("/p/" + token)).andExpect(status().isOk());

        // 运营封号（只改 status，不碰 deletedAt）
        User u = users.findById(owner.getId()).orElseThrow();
        u.deactivate();
        users.save(u);

        mvc.perform(get("/p/" + token))
                .andExpect(status().isNotFound());
    }

    /** 🛡 封号与「token 不存在」同样不可区分（防枚举口径对封号也成立）。 */
    @Test
    void suspendedAccountIsIndistinguishableFromUnknownToken() throws Exception {
        User owner = newUser();
        String token = createProfileAndGetToken(owner);
        User u = users.findById(owner.getId()).orElseThrow();
        u.deactivate();
        users.save(u);

        var suspended = mvc.perform(get("/p/" + token)).andReturn().getResponse();
        var unknown = mvc.perform(get("/p/no-such-token-at-all")).andReturn().getResponse();

        assertThat(suspended.getStatus()).isEqualTo(unknown.getStatus());
        assertThat(suspended.getContentAsString()).isEqualTo(unknown.getContentAsString());
    }

    /** 🛡 里程碑分享那条链路同样适用。 */
    @Test
    void suspendedAccountAlsoHidesMilestoneShare() throws Exception {
        User owner = newUser();
        createProfileAndGetToken(owner);
        User u = users.findById(owner.getId()).orElseThrow();
        u.deactivate();
        users.save(u);

        // 里程碑分享 token 不存在时本就 404；这里验的是「封号后也不会因为账号有效而放行」
        mvc.perform(get("/m/no-such-token-at-all")).andExpect(status().isNotFound());
    }

    /** ⚠️ 重新激活后应恢复可见（停用是可逆的，不能变成单向门）。 */
    @Test
    void reactivatedAccountBecomesVisibleAgain() throws Exception {
        User owner = newUser();
        String token = createProfileAndGetToken(owner);

        User u = users.findById(owner.getId()).orElseThrow();
        u.deactivate();
        users.save(u);
        mvc.perform(get("/p/" + token)).andExpect(status().isNotFound());

        u = users.findById(owner.getId()).orElseThrow();
        u.reactivate();
        users.save(u);
        mvc.perform(get("/p/" + token)).andExpect(status().isOk());
    }
}
