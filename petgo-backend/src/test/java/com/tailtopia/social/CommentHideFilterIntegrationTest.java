package com.tailtopia.social;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tailtopia.auth.domain.User;
import com.tailtopia.content.domain.ContentPost;
import com.tailtopia.content.domain.ContentType;
import com.tailtopia.content.domain.CommentModerationStatus;
import com.tailtopia.content.repository.CommentRepository;
import com.tailtopia.content.repository.ContentPostRepository;
import com.tailtopia.social.service.UserHideRelationService;
import com.tailtopia.support.ApiIntegrationTest;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * L1：评论区两条隐藏过滤 R1 / R2（Story 1.3，FR-94）—— 需 Docker postgres+redis。
 *
 * <p><b>为什么这层测试非有不可</b>：R1/R2 是写在 JPQL 里的安全攸关过滤，L0 单测只能验「service 把参数传对了」，
 * 验不了 SQL 本身跑不跑得通（新 {@code NOT EXISTS} 的语法、跨模块实体引用、以及游客分支的
 * <b>42P18 could not determine data type</b>）。这些只有真打 PostgreSQL 才会暴露。
 *
 * <p><b>三个角色贯穿全文</b>：<b>A</b> = 内容作者，<b>B</b> = 被隐藏的评论者，<b>C</b> = 与谁都无关的第三方。
 * R2 的判据是「内容作者」而不是「当前查看者」——做成按查看者判，C 照样看得见，<b>等于没做</b>，
 * 所以每条 R2 用例都<b>三个视角各看一遍</b>（外加游客）。
 */
class CommentHideFilterIntegrationTest extends ApiIntegrationTest {

    @Autowired
    private ContentPostRepository posts;

    @Autowired
    private UserHideRelationService hideService;

    @Autowired
    private CommentRepository comments;

    /**
     * ⚠️ 评论落库时是 {@code UNDER_REVIEW}，机器审核在**另一个线程**跑完才翻成 VISIBLE ——
     * 在那之前，除作者本人外谁都看不见它。
     *
     * <p>不等就断言的话，「过滤生效了」与「异步还没跑完」<b>看起来一模一样</b>：正向用例会偶发红，
     * 而所有「断言看不到」的负向用例会**假绿**。这道闸是 2026-08-16 全量回归时被一条偶发失败逼出来的
     * （Story 1.3 首版没有它）。
     */
    private void awaitVisible(long commentId) throws Exception {
        for (int i = 0; i < 100; i++) {
            var c = comments.findById(commentId).orElseThrow();
            if (c.getModerationStatus() == CommentModerationStatus.VISIBLE) {
                return;
            }
            Thread.sleep(30);
        }
        throw new AssertionError("评论 " + commentId + " 迟迟没走完审核，无法判断可见性过滤是否生效");
    }

    private ContentPost newPost(long authorId) {
        return posts.save(ContentPost.publish(authorId, ContentType.DAILY, null, "测试正文", List.of()));
    }

    /** 以某人身份发一级评论，返回评论 id。 */
    private long comment(long userId, long postId, String body) throws Exception {
        MvcResult r = mvc.perform(post("/api/v1/content-posts/" + postId + "/comments")
                        .header(HttpHeaders.AUTHORIZATION, userBearer(userId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"body\":\"" + body + "\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        long id = json.readTree(r.getResponse().getContentAsString()).get("id").asLong();
        awaitVisible(id);
        return id;
    }

    /** 以某人身份回复某条一级评论，返回回复 id。 */
    private long reply(long userId, long parentId, String body) throws Exception {
        MvcResult r = mvc.perform(post("/api/v1/comments/" + parentId + "/replies")
                        .header(HttpHeaders.AUTHORIZATION, userBearer(userId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"body\":\"" + body + "\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        long id = json.readTree(r.getResponse().getContentAsString()).get("id").asLong();
        awaitVisible(id);
        return id;
    }

    /** 某人（viewerId=null → 游客）看到的一级评论 id 列表。 */
    private List<Long> topLevelIdsAs(Long viewerId, long postId) throws Exception {
        MvcResult r = mvc.perform(as(get("/api/v1/content-posts/" + postId + "/comments"), viewerId))
                .andExpect(status().isOk())
                .andReturn();
        var items = json.readTree(r.getResponse().getContentAsString()).get("items");
        return java.util.stream.StreamSupport.stream(items.spliterator(), false)
                .map(n -> n.get("id").asLong())
                .toList();
    }

    /** 某人看到的某条一级评论的 replyCount（不在列表里 → null）。 */
    private Integer replyCountAs(Long viewerId, long postId, long commentId) throws Exception {
        MvcResult r = mvc.perform(as(get("/api/v1/content-posts/" + postId + "/comments"), viewerId))
                .andExpect(status().isOk())
                .andReturn();
        for (var n : json.readTree(r.getResponse().getContentAsString()).get("items")) {
            if (n.get("id").asLong() == commentId) {
                return n.get("replyCount").asInt();
            }
        }
        return null;
    }

    /** 某人看到的展开回复列表 id。 */
    private List<Long> expandedRepliesAs(Long viewerId, long parentId) throws Exception {
        MvcResult r = mvc.perform(as(get("/api/v1/comments/" + parentId + "/replies"), viewerId))
                .andExpect(status().isOk())
                .andReturn();
        var items = json.readTree(r.getResponse().getContentAsString()).get("items");
        return java.util.stream.StreamSupport.stream(items.spliterator(), false)
                .map(n -> n.get("id").asLong())
                .toList();
    }

    /** 某人看到的详情 commentCount。 */
    private long commentCountAs(Long viewerId, long postId) throws Exception {
        MvcResult r = mvc.perform(as(get("/api/v1/content-posts/" + postId), viewerId))
                .andExpect(status().isOk())
                .andReturn();
        return json.readTree(r.getResponse().getContentAsString()).get("commentCount").asLong();
    }

    /** viewerId=null 就是**不带任何 Authorization 头**的游客——游客分支的 42P18 全靠它踩出来。 */
    private MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder b, Long viewerId) {
        return viewerId == null ? b : b.header(HttpHeaders.AUTHORIZATION, userBearer(viewerId));
    }

    // ===== AC1 · R1 按查看者隐藏 =====

    @Test
    void ac1_r1_hidesCommentOnlyForTheViewerWhoHid() throws Exception {
        User a = newUser();
        User b = newUser();
        User c = newUser();
        long postId = newPost(a.getId()).getId();
        long cmt = comment(b.getId(), postId, "B 的评论");

        hideService.block(c.getId(), b.getId()); // C 拉黑 B

        assertThat(topLevelIdsAs(c.getId(), postId)).doesNotContain(cmt);   // C 看不到
        assertThat(topLevelIdsAs(a.getId(), postId)).contains(cmt);        // 内容作者照常看得到
        assertThat(topLevelIdsAs(b.getId(), postId)).contains(cmt);        // B 自己照常看得到
        assertThat(topLevelIdsAs(null, postId)).contains(cmt);             // 游客照常看得到
    }

    /** R1 不区分来源：举报产生的隐藏关系同样过滤（Story 2.1 靠这条自动复用）。 */
    @Test
    void ac1_r1_appliesToReportSourcedHideToo() throws Exception {
        User a = newUser();
        User b = newUser();
        User c = newUser();
        long postId = newPost(a.getId()).getId();
        long cmt = comment(b.getId(), postId, "B 的评论");

        hideService.hideByReport(c.getId(), b.getId()); // 来源 REPORT

        assertThat(topLevelIdsAs(c.getId(), postId)).doesNotContain(cmt);
        assertThat(topLevelIdsAs(a.getId(), postId)).contains(cmt);
    }

    // ===== AC2 · R2 影子评论（高风险点 R2）=====

    /**
     * ⚠️ 本 story 最核心的一条：判据是<b>内容作者</b>，不是当前查看者。
     * 三视角 + 游客各看一遍——写成按查看者判时，C 与游客这两条会立刻红。
     */
    @Test
    void ac2_r2_shadowsCommentForEveryoneExceptItsAuthor() throws Exception {
        User a = newUser();
        User b = newUser();
        User c = newUser();
        long postId = newPost(a.getId()).getId();
        long cmt = comment(b.getId(), postId, "B 在 A 的帖子下说话");

        hideService.block(a.getId(), b.getId()); // 内容作者 A 拉黑 B

        assertThat(topLevelIdsAs(a.getId(), postId)).doesNotContain(cmt);   // A 看不到
        assertThat(topLevelIdsAs(c.getId(), postId)).doesNotContain(cmt);   // ⚠️ 第三方也看不到
        assertThat(topLevelIdsAs(null, postId)).doesNotContain(cmt);        // ⚠️ 游客也看不到
        assertThat(topLevelIdsAs(b.getId(), postId)).contains(cmt);         // 只有 B 自己看得到
    }

    /** R2 只作用于「这位内容作者的地盘」：A 拉黑 B 不影响 B 在 C 的帖子下说话。 */
    @Test
    void ac2_r2_isScopedToThatAuthorsOwnPost() throws Exception {
        User a = newUser();
        User b = newUser();
        User c = newUser();
        long othersPost = newPost(c.getId()).getId();
        long cmt = comment(b.getId(), othersPost, "B 在 C 的帖子下说话");

        hideService.block(a.getId(), b.getId());

        assertThat(topLevelIdsAs(c.getId(), othersPost)).contains(cmt);
        assertThat(topLevelIdsAs(null, othersPost)).contains(cmt);
    }

    // ===== AC3 · 影子评论对评论者本人无感知 =====

    /**
     * B 自己那条要「与发布成功完全一致」：在列表里、正文原样、无任何标记字段变化。
     * 顺带钉死 DTO 没被加字段（AC8）——契约金标那边也锁着，这里是行为侧的第二道。
     */
    @Test
    void ac3_shadowedAuthorSeesOwnCommentUnchanged() throws Exception {
        User a = newUser();
        User b = newUser();
        long postId = newPost(a.getId()).getId();
        long cmt = comment(b.getId(), postId, "B 的原话");

        hideService.block(a.getId(), b.getId());

        mvc.perform(get("/api/v1/content-posts/" + postId + "/comments")
                        .header(HttpHeaders.AUTHORIZATION, userBearer(b.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].id").value(cmt))
                .andExpect(jsonPath("$.items[0].body").value("B 的原话"))
                .andExpect(jsonPath("$.items[0].moderationStatus").value("VISIBLE"));
    }

    /** AC3 后半：B 还要看得到别人对他那条的回复（否则他会发现「怎么没人理我」）。 */
    @Test
    void ac3_shadowedAuthorStillSeesRepliesToHisComment() throws Exception {
        User a = newUser();
        User b = newUser();
        User c = newUser();
        long postId = newPost(a.getId()).getId();
        long parent = comment(b.getId(), postId, "B 的评论");
        long rep = reply(c.getId(), parent, "C 回复 B");

        hideService.block(a.getId(), b.getId());

        assertThat(expandedRepliesAs(b.getId(), parent)).contains(rep);
        assertThat(replyCountAs(b.getId(), postId, parent)).isEqualTo(1);
    }

    // ===== AC4 · 回复串随父一并隐藏 =====

    @Test
    void ac4_replyChainDisappearsWithItsShadowedParent() throws Exception {
        User a = newUser();
        User b = newUser();
        User c = newUser();
        long postId = newPost(a.getId()).getId();
        long parent = comment(b.getId(), postId, "B 的评论");
        long rep = reply(c.getId(), parent, "C 回复 B");

        hideService.block(a.getId(), b.getId()); // R2 影子掉父

        // 父不在列表里 → 内嵌回复自然也不在（不出现「回复了某条看不见的评论」的孤儿回复）。
        assertThat(topLevelIdsAs(c.getId(), postId)).doesNotContain(parent);
        // 展开端点也必须空：它原本完全不校验父，是本 story 新加的那道闸。
        assertThat(expandedRepliesAs(c.getId(), parent)).doesNotContain(rep);
        assertThat(expandedRepliesAs(a.getId(), parent)).isEmpty();
        assertThat(expandedRepliesAs(null, parent)).isEmpty();
    }

    /** R1 同理：我拉黑的人写的父评论，他底下别人的回复我也不该看见。 */
    @Test
    void ac4_replyChainDisappearsWithR1HiddenParent() throws Exception {
        User a = newUser();
        User b = newUser();
        User c = newUser();
        long postId = newPost(a.getId()).getId();
        long parent = comment(b.getId(), postId, "B 的评论");
        long rep = reply(a.getId(), parent, "A 回复 B");

        hideService.block(c.getId(), b.getId()); // C 拉黑 B（R1）

        assertThat(topLevelIdsAs(c.getId(), postId)).doesNotContain(parent);
        assertThat(expandedRepliesAs(c.getId(), parent)).isEmpty();
        assertThat(expandedRepliesAs(a.getId(), parent)).contains(rep); // 与他人无关
    }

    /** 回复自身也受两条过滤：父可见，但回复者被影子 → 那条回复单独消失。 */
    @Test
    void ac4_shadowedReplyDisappearsWhileItsParentStays() throws Exception {
        User a = newUser();
        User b = newUser();
        User c = newUser();
        long postId = newPost(a.getId()).getId();
        long parent = comment(c.getId(), postId, "C 的评论");
        long rep = reply(b.getId(), parent, "B 的回复");

        hideService.block(a.getId(), b.getId());

        assertThat(topLevelIdsAs(c.getId(), postId)).contains(parent);
        assertThat(expandedRepliesAs(c.getId(), parent)).doesNotContain(rep);
        assertThat(expandedRepliesAs(b.getId(), parent)).contains(rep); // B 自己照常看得到
        assertThat(replyCountAs(c.getId(), postId, parent)).isZero();   // replyCount 天然随过滤
    }

    // ===== AC5 · 两条计数口径 =====

    /**
     * 5 条评论、其中 1 条被 R2 影子 → 对外 4、B 自己 5。
     * ⚠️ 数字必须与**实际渲染出来的条数**一致，否则就是「标题写 5、往下数只有 4」的穿帮。
     */
    @Test
    void ac5_shadowedCommentIsExcludedFromCommentCount() throws Exception {
        User a = newUser();
        User b = newUser();
        User c = newUser();
        long postId = newPost(a.getId()).getId();
        for (int i = 0; i < 4; i++) {
            comment(c.getId(), postId, "C 的第 " + i + " 条");
        }
        comment(b.getId(), postId, "B 的评论");

        hideService.block(a.getId(), b.getId());

        assertThat(commentCountAs(c.getId(), postId)).isEqualTo(4);
        assertThat(commentCountAs(null, postId)).isEqualTo(4);
        assertThat(commentCountAs(b.getId(), postId)).isEqualTo(5); // 影子机制固有 +1，可接受
        // 数字与渲染条数一致。
        assertThat(topLevelIdsAs(c.getId(), postId)).hasSize(4);
    }

    /**
     * R1 侧同样要求「数字 == 渲染条数」。
     *
     * <p>⚠️ 这条对应 AD-13 里那句自相矛盾的表述（「同步套用 R1 + R2」vs「R1 隐藏的照常计入」）。
     * 裁定：<b>面向查看者的 commentCount 套 R1</b>（否则出现该条自己列为首要防范的穿帮）；
     * 「R1 照常计入」属<b>平台口径 / 互动量统计</b>那一层，见 {@code countByRealAuthor}。
     * 若产品改判，改的是 {@code countVisibleForViewer} 的 R1 那两行 WHERE，本用例随之调整。
     */
    @Test
    void ac5_r1HiddenCommentAlsoKeepsCountAndRenderedRowsInSync() throws Exception {
        User a = newUser();
        User b = newUser();
        User c = newUser();
        long postId = newPost(a.getId()).getId();
        comment(a.getId(), postId, "A 自己的评论");
        comment(b.getId(), postId, "B 的评论");

        hideService.block(c.getId(), b.getId());

        assertThat(topLevelIdsAs(c.getId(), postId)).hasSize(1);
        assertThat(commentCountAs(c.getId(), postId)).isEqualTo(1);
        assertThat(commentCountAs(null, postId)).isEqualTo(2); // 其他人不受影响
    }

    // ===== AC6 · 被拉黑方仍可「评论成功」 =====

    /** 写路径一行不改：B 提交评论照常 201 + 正常落库，只是读的时候被 R2 过滤掉。 */
    @Test
    void ac6_blockedUserStillCommentsSuccessfully() throws Exception {
        User a = newUser();
        User b = newUser();
        long postId = newPost(a.getId()).getId();

        hideService.block(a.getId(), b.getId()); // 先拉黑，再评论

        long cmt = comment(b.getId(), postId, "B 被拉黑之后发的");

        assertThat(topLevelIdsAs(b.getId(), postId)).contains(cmt); // 落库了、他自己看得到
        assertThat(topLevelIdsAs(a.getId(), postId)).doesNotContain(cmt);
        assertThat(topLevelIdsAs(null, postId)).doesNotContain(cmt);
    }

    // ===== 叠加 =====

    /** R1 与 R2 正交：同时命中不会互相抵消（两条只会隐藏、永不强制显示）。 */
    @Test
    void r1AndR2StackWithoutCancellingEachOther() throws Exception {
        User a = newUser();
        User b = newUser();
        User c = newUser();
        long postId = newPost(a.getId()).getId();
        long cmt = comment(b.getId(), postId, "B 的评论");

        hideService.block(a.getId(), b.getId()); // R2
        hideService.block(c.getId(), b.getId()); // R1

        assertThat(topLevelIdsAs(c.getId(), postId)).doesNotContain(cmt);
        assertThat(topLevelIdsAs(a.getId(), postId)).doesNotContain(cmt);
        assertThat(topLevelIdsAs(b.getId(), postId)).contains(cmt);
    }

    /** 解除拉黑后立即恢复可见（隐藏关系是实时查库，无缓存 —— AD-18）。 */
    @Test
    void unblockRestoresVisibilityImmediately() throws Exception {
        User a = newUser();
        User b = newUser();
        long postId = newPost(a.getId()).getId();
        long cmt = comment(b.getId(), postId, "B 的评论");

        hideService.block(a.getId(), b.getId());
        assertThat(topLevelIdsAs(a.getId(), postId)).doesNotContain(cmt);

        hideService.unblock(a.getId(), b.getId());
        assertThat(topLevelIdsAs(a.getId(), postId)).contains(cmt);
    }

    /** 举报来源的隐藏关系不会被解除拉黑顺手删掉（三元唯一键，Story 1.1 AC4）——评论侧同样成立。 */
    @Test
    void unblockDoesNotRestoreReportSourcedHide() throws Exception {
        User a = newUser();
        User b = newUser();
        long postId = newPost(a.getId()).getId();
        long cmt = comment(b.getId(), postId, "B 的评论");

        hideService.block(a.getId(), b.getId());
        hideService.hideByReport(a.getId(), b.getId());
        hideService.unblock(a.getId(), b.getId()); // 只删 BLOCK 行，REPORT 行仍在

        assertThat(topLevelIdsAs(a.getId(), postId)).doesNotContain(cmt);
    }
}
