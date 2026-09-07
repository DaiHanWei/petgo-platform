package com.tailtopia.content.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tailtopia.auth.domain.User;
import com.tailtopia.content.domain.ContentPost;
import com.tailtopia.content.domain.ContentType;
import com.tailtopia.content.domain.ContentVisibility;
import com.tailtopia.content.repository.ContentPostRepository;
import com.tailtopia.content.repository.ContentShareRepository;
import com.tailtopia.support.ApiIntegrationTest;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;

/**
 * L1 集成：单条内容分享（Story 9.3 · FR-73 · AD-15 Rule 5/6）。
 *
 * <p>🛡 <b>本类含两条安全攸关断言，不得弱化</b>：
 * <ul>
 *   <li><b>AC2 隐私边界</b>：公开投影里<b>不许</b>出现任何 id（postId / authorId / petId / cardToken）。
 *       「落地页上没放入口」不算数 —— 只要投影里有把手，将来谁加个「看更多」就能把整本档案漏出去。</li>
 *   <li><b>AC3 刻意的反向</b>：私密 Diary 被作者主动分享后，访客<b>可见</b>。
 *       这与「访客浏览整本档案看不到私密内容」方向相反，是刻意的（OQ-18 / AD-15 Rule 6）。
 *       有人来"统一口径"时这条会红 —— <b>那不是缺陷</b>。</li>
 * </ul>
 */
class ContentShareIntegrationTest extends ApiIntegrationTest {

    @Autowired
    private ContentPostRepository posts;

    @Autowired
    private ContentShareRepository shares;

    private ContentPost savePost(long authorId, String text) {
        return posts.save(ContentPost.publish(authorId, ContentType.GROWTH_MOMENT, null, text,
                List.of("https://img.example/1.jpg")));
    }

    /** 只数**这条内容**的分享行。共享库里 findAll() 会看见邻居用例造的数据。 */
    private List<com.tailtopia.content.domain.ContentShare> sharesOf(ContentPost post) {
        return shares.findAll().stream()
                .filter(s -> java.util.Objects.equals(s.getContentPostId(), post.getId()))
                .toList();
    }

    private String createLink(User author, ContentPost post) throws Exception {
        String body = mvc.perform(post("/api/v1/content-posts/" + post.getId() + "/share-link")
                        .header(HttpHeaders.AUTHORIZATION, userBearer(author.getId())))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("shareToken").asText();
    }

    // ——————————————————— 建链接 ———————————————————

    @Test
    void authorGetsAnUnguessableTokenAndRepeatShareReusesIt() throws Exception {
        User author = newUser();
        ContentPost p = savePost(author.getId(), "分享我这条");

        String first = createLink(author, p);
        // 不可枚举：既不是顺序 id，也不由它派生。
        assertThat(first).hasSizeGreaterThanOrEqualTo(22);
        assertThat(first).doesNotContain(String.valueOf(p.getId()));

        // 幂等：再点一次分享复用同一 token，不会每点一次就多一条链接。
        String second = createLink(author, p);
        assertThat(second).isEqualTo(first);
        // ⚠️ 按**这条内容**数，不用 findAll()：同一测试类里别的用例也在建分享行，
        // 全库口径的断言会被邻居污染（本条第一版就是这么错的）。
        assertThat(sharesOf(p)).hasSize(1);
    }

    /**
     * 🔴 **非作者可以分享别人的「公开」内容**（产品 2026-08-27 放开）。
     *
     * <p>起因：信息流里加了分享入口，而流里绝大多数是别人的帖 ——
     * 原先「只能分享自己的」会让那个入口在多数卡片上直接 404 报错（实机踩到）。
     *
     * <p>🛡 这不是把可见性护栏改松：能被非作者分享的只有**本来就公开**的内容，
     * 分享链接暴露的东西一点没多（那条帖在信息流里人人可见）。
     */
    @Test
    void nonAuthorCanCreateALinkForSomeoneElsesPublicPost() throws Exception {
        User author = newUser();
        User other = newUser();
        ContentPost p = savePost(author.getId(), "别人的公开内容");

        mvc.perform(post("/api/v1/content-posts/" + p.getId() + "/share-link")
                        .header(HttpHeaders.AUTHORIZATION, userBearer(other.getId())))
                .andExpect(status().isCreated());
        assertThat(sharesOf(p)).hasSize(1);
    }

    /**
     * 🔴 **私密内容仍然只有作者本人能分享。**
     *
     * <p>这一条是上面那次放开的边界，不能一起放：私密内容被分享后访客可见（AD-15 Rule 6），
     * 而那条规则的全部依据是「**作者自己**按下了分享键 = 授权」——
     * 换成别人按下就不成立了，等于让第三方把某人的私密日记公开出去。
     *
     * <p>⚠️ 404 而不是 403：403 会变成「这个 id 是私密内容」的探测器
     * （与本类其它失效分支同一口径）。
     */
    @Test
    void nonAuthorStillCannotShareSomeoneElsesPrivatePost() throws Exception {
        User author = newUser();
        User other = newUser();
        ContentPost p = savePost(author.getId(), "别人的私密日记");
        p.setVisibility(ContentVisibility.PRIVATE);
        posts.save(p);

        mvc.perform(post("/api/v1/content-posts/" + p.getId() + "/share-link")
                        .header(HttpHeaders.AUTHORIZATION, userBearer(other.getId())))
                .andExpect(status().isNotFound());
        assertThat(sharesOf(p))
                .as("🔴 第三方把别人的私密日记公开出去了 —— Rule 6 的授权前提被绕过")
                .isEmpty();
    }

    @Test
    void anonymousCannotCreateALink() throws Exception {
        User author = newUser();
        ContentPost p = savePost(author.getId(), "内容");
        mvc.perform(post("/api/v1/content-posts/" + p.getId() + "/share-link"))
                .andExpect(status().isUnauthorized());
    }

    // ——————————————————— 公开落地：AC2 隐私边界 ———————————————————

    @Test
    void publicProjectionCarriesNoHandleToAnythingElse() throws Exception {
        User author = newUser();
        ContentPost p = savePost(author.getId(), "只看这一条");
        String token = createLink(author, p);

        String body = mvc.perform(get("/api/v1/public/shared-posts/" + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.body").value("只看这一条"))
                .andExpect(jsonPath("$.type").value("GROWTH_MOMENT"))
                .andReturn().getResponse().getContentAsString();

        // 🛡 结构上就拿不到把手：没有任何 id 字段。
        var node = json.readTree(body);
        for (String forbidden : List.of("id", "postId", "authorId", "petId", "cardToken",
                "petProfileId", "contentPostId")) {
            assertThat(node.has(forbidden))
                    .as("公开投影不得包含 %s —— 有了它，将来加个「看更多」就能漏出整本档案", forbidden)
                    .isFalse();
        }
        // 站内互动也不外露（未登录访客既看不到也不该看到）。
        for (String forbidden : List.of("likeCount", "commentCount", "liked", "isAuthor")) {
            assertThat(node.has(forbidden)).as("公开投影不该有 %s", forbidden).isFalse();
        }
    }

    @Test
    void landingPageShowsOnlyThatOnePostAndLinksNowhereElse() throws Exception {
        User author = newUser();
        ContentPost p = savePost(author.getId(), "落地页只有我");
        String token = createLink(author, p);

        String html = mvc.perform(get("/c/" + token))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("落地页只有我")))
                .andReturn().getResponse().getContentAsString();

        // 🛡 页面上没有任何通往该宠物其它内容的路径。
        assertThat(html).doesNotContain("/p/");   // 名片页（整本档案只读视图）
        assertThat(html).doesNotContain("/m/");   // 里程碑分享页
        // 🔴 **不要写成 doesNotContain(帖子 id 的字符串)**。id 小的时候（新库里第一条就是 3）
        // 单个数字在任何 HTML 的 CSS 色值里都有（#2E2A45 / rgba(46,42,69,.10)），
        // 那条断言只在 id 恰好是个不常见的大数时才绿 —— 是靠运气过的假绿。
        // 真正要表达的是「页面不带按 id 寻址的链接」，就直接断言那些路径形状。
        assertThat(html).doesNotContain("/api/v1/content-posts/");
        assertThat(html).doesNotContain("/content/" + p.getId());
        // 不进搜索引擎（防枚举）。
        assertThat(html).contains("noindex");
    }

    @Test
    void sharedPostLinkTypeIsDistinctFromPetCardLink() throws Exception {
        User author = newUser();
        ContentPost p = savePost(author.getId(), "内容");
        String token = createLink(author, p);

        // 🔴 单条分享的 token 不能被名片路由认走 —— 两种链接必须是两个类型、两个落点。
        mvc.perform(get("/p/" + token)).andExpect(status().isNotFound());
        // 反过来同理：随便一个不属于 content_shares 的 token 打 /c/ 也是失效页。
        mvc.perform(get("/c/definitely-not-a-content-share-token"))
                .andExpect(status().isNotFound());
    }

    // ——————————————————— AC3：私密内容被主动分享后可见 ———————————————————

    /**
     * 🛡 <b>刻意与 Epic 2 反向</b>（AD-15 Rule 6）：Epic 2 管「访客浏览整本档案」→ 私密不可见；
     * 本条管「作者点了分享的那一条」→ <b>可见</b>。主动分享即授权。
     *
     * <p>这条口径不是本 story 新发明的：{@code ContentVisibility} 的注释里早已写明
     * 「visibility 约束平台自动分发，不约束用户自己按下分享键的行为」（OQ-18，2026-08-03 拍板）。
     * 谁来「统一口径」这里就会红 —— 那不是缺陷。
     */
    @Test
    void privateDiaryStaysVisibleOnceTheAuthorSharesIt() throws Exception {
        User author = newUser();
        ContentPost p = savePost(author.getId(), "这是私密日记");
        p.setVisibility(ContentVisibility.PRIVATE);
        posts.save(p);

        String token = createLink(author, p);

        mvc.perform(get("/api/v1/public/shared-posts/" + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.body").value("这是私密日记"));
        mvc.perform(get("/c/" + token)).andExpect(status().isOk());
    }

    // ——————————————————— 失效收敛（不区分原因） ———————————————————

    @Test
    void deletedPostFallsBackToTheGonePageNotAnErrorThatRevealsWhy() throws Exception {
        User author = newUser();
        ContentPost p = savePost(author.getId(), "待删");
        String token = createLink(author, p);

        p.softDelete();
        posts.save(p);

        mvc.perform(get("/api/v1/public/shared-posts/" + token)).andExpect(status().isNotFound());
        // H5 走失效页（404 + 复用名片失效页），与"token 根本不存在"同一响应。
        mvc.perform(get("/c/" + token)).andExpect(status().isNotFound());
    }

    @Test
    void takenDownPostAlsoFallsBackToGone() throws Exception {
        User author = newUser();
        ContentPost p = savePost(author.getId(), "被举报下架");
        String token = createLink(author, p);

        // 举报 P0 预处置：PUBLISHED → UNDER_REVIEW + reportHiddenAt（内容不删）。
        p.applyReportHold();
        posts.save(p);

        mvc.perform(get("/api/v1/public/shared-posts/" + token)).andExpect(status().isNotFound());
    }

    @Test
    void unpublishedPostCannotBeSharedAtAll() throws Exception {
        User author = newUser();
        ContentPost p = posts.save(ContentPost.pendingReview(author.getId(), ContentType.DAILY,
                null, "审核挂起", List.of(), null));

        // 自己都还没公开的内容不该拿到对外链接。
        mvc.perform(post("/api/v1/content-posts/" + p.getId() + "/share-link")
                        .header(HttpHeaders.AUTHORIZATION, userBearer(author.getId())))
                .andExpect(status().isUnprocessableEntity());
    }
}
