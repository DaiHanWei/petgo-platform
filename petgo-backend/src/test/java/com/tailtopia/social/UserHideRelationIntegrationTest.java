package com.tailtopia.social;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tailtopia.auth.domain.User;
import com.tailtopia.content.domain.ContentType;
import com.tailtopia.content.domain.ContentPost;
import com.tailtopia.content.repository.ContentPostRepository;
import com.tailtopia.social.domain.HideSource;
import com.tailtopia.social.domain.UserHideRelation;
import com.tailtopia.social.repository.UserHideRelationRepository;
import com.tailtopia.social.service.UserHideRelationService;
import com.tailtopia.support.ApiIntegrationTest;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

/**
 * L1：隐藏关系（Story 1.1，FR-94）—— 需 Docker postgres+redis。
 *
 * <p>覆盖 AC1 落库 / AC3 接口幂等 / AC4 跨源不吞 / AC5 首页过滤 / AC6 主页拦截 /
 * <b>AC7 拦截只认主动拉黑（高风险点 R1）</b> / AC8a·AC8b·AC8c 单向屏蔽边界 / AC10 详情 404。
 */
class UserHideRelationIntegrationTest extends ApiIntegrationTest {

    @Autowired
    private UserHideRelationRepository relations;

    @Autowired
    private UserHideRelationService hideService;

    @Autowired
    private ContentPostRepository posts;

    private ContentPost newPost(long authorId) {
        return posts.save(ContentPost.publish(authorId, ContentType.DAILY, null, "测试正文", List.of()));
    }

    private String blockBody(long targetUserId) {
        return "{\"targetUserId\":" + targetUserId + "}";
    }

    // ===== AC1 · 隐藏关系落库 =====

    @Test
    void blockPersistsRelationWithBlockSource() throws Exception {
        User a = newUser();
        User b = newUser();

        mvc.perform(post("/api/v1/me/blocked-users")
                        .header(HttpHeaders.AUTHORIZATION, userBearer(a.getId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(blockBody(b.getId())))
                .andExpect(status().isNoContent());

        var row = relations.findByHolderIdAndTargetIdAndSource(a.getId(), b.getId(), HideSource.BLOCK);
        assertThat(row).isPresent();
        assertThat(row.get().getCreatedAt()).isNotNull();
        assertThat(row.get().getUpdatedAt()).isNotNull();
    }

    // ===== AC3 · 拉黑与解除的接口行为 =====

    /** 重复拉黑幂等：不报错、不新增记录、**不刷新拉黑时间**（避免反复点击顶到列表最前）。 */
    @Test
    void repeatedBlockIsIdempotentAndDoesNotRefreshTimestamp() throws Exception {
        User a = newUser();
        User b = newUser();

        mvc.perform(post("/api/v1/me/blocked-users")
                        .header(HttpHeaders.AUTHORIZATION, userBearer(a.getId()))
                        .contentType(MediaType.APPLICATION_JSON).content(blockBody(b.getId())))
                .andExpect(status().isNoContent());
        var first = relations.findByHolderIdAndTargetIdAndSource(a.getId(), b.getId(), HideSource.BLOCK)
                .orElseThrow();

        mvc.perform(post("/api/v1/me/blocked-users")
                        .header(HttpHeaders.AUTHORIZATION, userBearer(a.getId()))
                        .contentType(MediaType.APPLICATION_JSON).content(blockBody(b.getId())))
                .andExpect(status().isNoContent());

        var all = relations.findByHolderIdAndSourceOrderByCreatedAtDesc(a.getId(), HideSource.BLOCK);
        assertThat(all).hasSize(1);
        assertThat(all.get(0).getId()).isEqualTo(first.getId());
        assertThat(all.get(0).getCreatedAt()).isEqualTo(first.getCreatedAt()); // 时间戳未刷新
    }

    /** 解除只删 BLOCK 行；重复解除静默成功。 */
    @Test
    void unblockRemovesOnlyBlockRowAndRepeatIsSilent() throws Exception {
        User a = newUser();
        User b = newUser();
        hideService.block(a.getId(), b.getId());
        hideService.hideByReport(a.getId(), b.getId());

        mvc.perform(delete("/api/v1/me/blocked-users/" + b.getId())
                        .header(HttpHeaders.AUTHORIZATION, userBearer(a.getId())))
                .andExpect(status().isNoContent());

        assertThat(relations.existsByHolderIdAndTargetIdAndSource(a.getId(), b.getId(), HideSource.BLOCK))
                .isFalse();
        // REPORT 行永不删除
        assertThat(relations.existsByHolderIdAndTargetIdAndSource(a.getId(), b.getId(), HideSource.REPORT))
                .isTrue();

        // 重复解除静默成功
        mvc.perform(delete("/api/v1/me/blocked-users/" + b.getId())
                        .header(HttpHeaders.AUTHORIZATION, userBearer(a.getId())))
                .andExpect(status().isNoContent());
    }

    @Test
    void cannotBlockSelf() throws Exception {
        User a = newUser();
        mvc.perform(post("/api/v1/me/blocked-users")
                        .header(HttpHeaders.AUTHORIZATION, userBearer(a.getId()))
                        .contentType(MediaType.APPLICATION_JSON).content(blockBody(a.getId())))
                .andExpect(status().is4xxClientError());
    }

    @Test
    void blockWithoutTokenReturns401() throws Exception {
        User b = newUser();
        mvc.perform(post("/api/v1/me/blocked-users")
                        .contentType(MediaType.APPLICATION_JSON).content(blockBody(b.getId())))
                .andExpect(status().isUnauthorized());
    }

    // ===== AC4 · 幂等只在同源之间生效 =====

    /**
     * ⚠️ 已存在 REPORT 行时发起主动拉黑，**必须照常新增 BLOCK 行**，两条并存。
     *
     * <p>写成二元唯一键或「已存在隐藏就跳过」，会让用户点了拉黑、Toast 说「请在黑名单里查看」，
     * 去了却找不到人（C-91）。
     */
    @Test
    void blockAfterReportCreatesSecondRowNotSwallowed() {
        User a = newUser();
        User b = newUser();

        hideService.hideByReport(a.getId(), b.getId());
        hideService.block(a.getId(), b.getId());

        assertThat(relations.existsByHolderIdAndTargetIdAndSource(a.getId(), b.getId(), HideSource.REPORT))
                .isTrue();
        assertThat(relations.existsByHolderIdAndTargetIdAndSource(a.getId(), b.getId(), HideSource.BLOCK))
                .isTrue();
        // 出现在黑名单页（只收录含 BLOCK 的条目）
        assertThat(relations.findByHolderIdAndSourceOrderByCreatedAtDesc(a.getId(), HideSource.BLOCK))
                .hasSize(1);
    }

    /** 先拉黑后举报：举报不得触碰已有 BLOCK 行的任何字段（否则黑名单排序被搅乱）。 */
    @Test
    void reportAfterBlockDoesNotTouchBlockRow() {
        User a = newUser();
        User b = newUser();
        hideService.block(a.getId(), b.getId());
        UserHideRelation before = relations
                .findByHolderIdAndTargetIdAndSource(a.getId(), b.getId(), HideSource.BLOCK).orElseThrow();

        hideService.hideByReport(a.getId(), b.getId());

        UserHideRelation after = relations
                .findByHolderIdAndTargetIdAndSource(a.getId(), b.getId(), HideSource.BLOCK).orElseThrow();
        assertThat(after.getId()).isEqualTo(before.getId());
        assertThat(after.getCreatedAt()).isEqualTo(before.getCreatedAt());
        assertThat(after.getUpdatedAt()).isEqualTo(before.getUpdatedAt());
    }

    // ===== AC5 · 首页过滤 =====

    @Test
    void feedExcludesHiddenAuthorContentAndGuestUnaffected() throws Exception {
        User a = newUser();
        User b = newUser();
        long p1 = newPost(b.getId()).getId();
        long p2 = newPost(b.getId()).getId();

        hideService.block(a.getId(), b.getId());

        String feed = mvc.perform(get("/api/v1/content-posts")
                        .header(HttpHeaders.AUTHORIZATION, userBearer(a.getId())))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        var ids = new java.util.HashSet<Long>();
        json.readTree(feed).get("items").forEach(n -> ids.add(n.get("id").asLong()));
        assertThat(ids).doesNotContain(p1, p2);

        // 游客（无 token）不受影响
        String guestFeed = mvc.perform(get("/api/v1/content-posts"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        var guestIds = new java.util.HashSet<Long>();
        json.readTree(guestFeed).get("items").forEach(n -> guestIds.add(n.get("id").asLong()));
        assertThat(guestIds).contains(p1, p2);
    }

    /** 举报隐藏同样过滤 Feed（AC5 不区分 source）。 */
    @Test
    void feedAlsoExcludesReportHiddenAuthor() throws Exception {
        User a = newUser();
        User b = newUser();
        long p1 = newPost(b.getId()).getId();

        hideService.hideByReport(a.getId(), b.getId());

        String feed = mvc.perform(get("/api/v1/content-posts")
                        .header(HttpHeaders.AUTHORIZATION, userBearer(a.getId())))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        var ids = new java.util.HashSet<Long>();
        json.readTree(feed).get("items").forEach(n -> ids.add(n.get("id").asLong()));
        assertThat(ids).doesNotContain(p1);
    }

    // ===== AC6 / AC7 · 主页访问拦截 =====

    @Test
    void miniProfileBlockedReturns403WithTypeAndNoProfileFields() throws Exception {
        User a = newUser();
        User b = newUser();
        hideService.block(a.getId(), b.getId());

        mvc.perform(get("/api/v1/users/" + b.getId() + "/mini-profile")
                        .header(HttpHeaders.AUTHORIZATION, userBearer(a.getId())))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.type").value("https://petgo/errors/blocked-user"))
                // 不外泄任何展示字段
                .andExpect(jsonPath("$.nickname").doesNotExist())
                .andExpect(jsonPath("$.avatarUrl").doesNotExist())
                .andExpect(jsonPath("$.signature").doesNotExist())
                .andExpect(jsonPath("$.postCount").doesNotExist());
    }

    /**
     * ⚠️ <b>AC7 / 高风险点 R1</b>：只举报过、未主动拉黑 → 主页<b>照常 200 返回卡片数据</b>。
     *
     * <p>把拦截条件写成「存在隐藏关系即拦」会一并把举报隐藏拦掉 ——「已举报」状态无处显示、
     * 重复举报无入口，FR-58 闭环当场作废。
     */
    @Test
    void miniProfileReportHiddenIsNotBlocked() throws Exception {
        User a = newUser();
        User b = newUser();
        hideService.hideByReport(a.getId(), b.getId());

        mvc.perform(get("/api/v1/users/" + b.getId() + "/mini-profile")
                        .header(HttpHeaders.AUTHORIZATION, userBearer(a.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.isDeactivated").value(false));
    }

    /** 游客请求迷你主页行为完全不变（端点保持 permitAll）。 */
    @Test
    void miniProfileGuestUnaffected() throws Exception {
        User b = newUser();
        mvc.perform(get("/api/v1/users/" + b.getId() + "/mini-profile"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.isDeactivated").value(false));
    }

    // ===== AC10 · 内容详情页同样不可达 =====

    @Test
    void detailOfHiddenAuthorReturns404ForBothSources() throws Exception {
        User a = newUser();
        User b = newUser();
        User c = newUser();
        long blockedPost = newPost(b.getId()).getId();
        long reportedPost = newPost(c.getId()).getId();

        hideService.block(a.getId(), b.getId());
        hideService.hideByReport(a.getId(), c.getId());

        mvc.perform(get("/api/v1/content-posts/" + blockedPost)
                        .header(HttpHeaders.AUTHORIZATION, userBearer(a.getId())))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/content-posts/" + reportedPost)
                        .header(HttpHeaders.AUTHORIZATION, userBearer(a.getId())))
                .andExpect(status().isNotFound());

        // 游客照常可见
        mvc.perform(get("/api/v1/content-posts/" + blockedPost)).andExpect(status().isOk());
    }

    // ===== AC8a / AC8b / AC8c · 单向屏蔽边界（AD-20），三条各自独立验收 =====

    /** AC8a：被拉黑方一侧「仍然可以」—— 仍可查看发起方内容、仍可点赞且照常计数。 */
    @Test
    void ac8a_blockedPartyCanStillViewAndLike() throws Exception {
        User a = newUser();
        User b = newUser();
        long aPost = newPost(a.getId()).getId();
        hideService.block(a.getId(), b.getId());

        // B 仍可查看 A 的内容详情
        mvc.perform(get("/api/v1/content-posts/" + aPost)
                        .header(HttpHeaders.AUTHORIZATION, userBearer(b.getId())))
                .andExpect(status().isOk());

        // B 仍可点赞 A 的内容，且照常计入
        mvc.perform(post("/api/v1/content-posts/" + aPost + "/like")
                        .header(HttpHeaders.AUTHORIZATION, userBearer(b.getId())))
                .andExpect(status().is2xxSuccessful());

        String detail = mvc.perform(get("/api/v1/content-posts/" + aPost)
                        .header(HttpHeaders.AUTHORIZATION, userBearer(b.getId())))
                .andReturn().getResponse().getContentAsString();
        assertThat(json.readTree(detail).get("likeCount").asLong()).isEqualTo(1L);
    }

    /** AC8b：统计与历史数据一概不动 —— 拉黑不回溯清理已产生的赞。 */
    @Test
    void ac8b_statsAndHistoryUntouchedByBlock() throws Exception {
        User a = newUser();
        User b = newUser();
        long aPost = newPost(a.getId()).getId();

        // 拉黑之前 B 先点了赞
        mvc.perform(post("/api/v1/content-posts/" + aPost + "/like")
                        .header(HttpHeaders.AUTHORIZATION, userBearer(b.getId())))
                .andExpect(status().is2xxSuccessful());

        hideService.block(a.getId(), b.getId());

        // 拉黑后该赞仍在、点赞数不因拉黑扣减
        String detail = mvc.perform(get("/api/v1/content-posts/" + aPost)
                        .header(HttpHeaders.AUTHORIZATION, userBearer(a.getId())))
                .andReturn().getResponse().getContentAsString();
        assertThat(json.readTree(detail).get("likeCount").asLong()).isEqualTo(1L);
    }

    /** AC8c：适用范围边界 —— 不设上限；游客与未登录路径不做拉黑过滤。 */
    @Test
    void ac8c_noLimitAndGuestNotFiltered() throws Exception {
        User a = newUser();
        // 不设数量上限：连续拉黑多人均成功
        for (int i = 0; i < 5; i++) {
            User t = newUser();
            mvc.perform(post("/api/v1/me/blocked-users")
                            .header(HttpHeaders.AUTHORIZATION, userBearer(a.getId()))
                            .contentType(MediaType.APPLICATION_JSON).content(blockBody(t.getId())))
                    .andExpect(status().isNoContent());
        }
        assertThat(relations.findByHolderIdAndSourceOrderByCreatedAtDesc(a.getId(), HideSource.BLOCK))
                .hasSize(5);

        // 游客 Feed 不做拉黑过滤（无登录身份可用）
        mvc.perform(get("/api/v1/content-posts")).andExpect(status().isOk());
    }
}
