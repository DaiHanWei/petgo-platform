package com.tailtopia.admin.moderation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.tailtopia.admin.account.domain.AdminAccount;
import com.tailtopia.admin.account.domain.AdminAccountType;
import com.tailtopia.admin.account.repository.AdminAccountRepository;
import com.tailtopia.admin.moderation.service.AdminContentDetailService;
import com.tailtopia.admin.service.AdminUserDetails;
import com.tailtopia.content.domain.Comment;
import com.tailtopia.content.domain.ContentLike;
import com.tailtopia.content.domain.ContentPost;
import com.tailtopia.content.domain.ContentType;
import com.tailtopia.content.repository.CommentRepository;
import com.tailtopia.content.repository.ContentLikeRepository;
import com.tailtopia.content.repository.ContentPostRepository;
import com.tailtopia.shared.error.AppException;
import com.tailtopia.support.ApiIntegrationTest;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;

/**
 * L1：内容详情只读聚合（2026-09-02，需 Docker postgres+redis）。
 * 与 App 详情同一批元素 + 后台状态；评论后台全量口径（含已删/已下架，标注状态）。
 */
class AdminContentDetailIntegrationTest extends ApiIntegrationTest {

    @Autowired
    private AdminContentDetailService detailService;
    @Autowired
    private ContentPostRepository posts;
    @Autowired
    private CommentRepository comments;
    @Autowired
    private ContentLikeRepository likes;
    @Autowired
    private AdminAccountRepository adminAccounts;

    private long newPost(long authorId, String text, List<String> images) {
        return posts.save(ContentPost.publish(authorId, ContentType.DAILY, null, text, images))
                .getId();
    }

    @Test
    void detailAggregatesPostAuthorCountsTagsAndComments() {
        var author = newUser();
        long postId = newPost(author.getId(), "kucing detail-" + SEQ.incrementAndGet(),
                List.of("https://cdn/x.jpg", "https://cdn/y.jpg"));
        var commenter = newUser();
        Comment top = comments.save(Comment.create(postId, null, commenter.getId(), "一级评论"));
        for (int i = 0; i < 5; i++) {
            comments.save(Comment.create(postId, top.getId(), commenter.getId(), "回复 " + i));
        }
        likes.save(ContentLike.of(postId, commenter.getId()));

        var d = detailService.detail(postId, 0, null);

        assertThat(d.post().id()).isEqualTo(postId);
        assertThat(d.post().text()).contains("kucing detail");
        assertThat(d.post().imageUrls()).hasSize(2);
        assertThat(d.author().nickname()).isEqualTo(author.getNickname());
        assertThat(d.likeCount()).isEqualTo(1);
        assertThat(d.commentCount()).isEqualTo(6); // 一级 1 + 二级 5（未删口径）
        // 楼中楼收起：只带前 3 条，但总数如实（2026-09-02 产品定）。
        var cm = d.comments().get(0);
        assertThat(cm.replies()).hasSize(3);
        assertThat(cm.replyTotal()).isEqualTo(5);
        assertThat(cm.authorName()).isEqualTo(commenter.getNickname());

        // 展开：全部 5 条。
        var expanded = detailService.detail(postId, 0, cm.id());
        assertThat(expanded.comments().get(0).replies()).hasSize(5);
    }

    /** 已删/已下架评论**照列并标注状态**——后台全量视角，不替运营隐藏（2026-09-02 产品定）。 */
    @Test
    void deletedAndTakenDownCommentsAreListedWithStatus() {
        long postId = newPost(newUser().getId(), "状态标注-" + SEQ.incrementAndGet(), List.of());
        long commenter = newUser().getId();
        Comment normal = comments.save(Comment.create(postId, null, commenter, "正常"));
        Comment taken = comments.save(Comment.create(postId, null, commenter, "被下架"));
        taken.takedown();
        comments.save(taken);
        Comment deleted = comments.save(Comment.create(postId, null, commenter, "被删除"));
        deleted.softDelete();
        comments.save(deleted);

        var d = detailService.detail(postId, 0, null);

        assertThat(d.comments()).hasSize(3); // 三条都在
        var byId = d.comments().stream().collect(java.util.stream.Collectors
                .toMap(AdminContentDetailService.CommentView::id, v -> v));
        assertThat(byId.get(normal.getId()).status()).isEqualTo("VISIBLE");
        assertThat(byId.get(taken.getId()).status()).isEqualTo("TAKEN_DOWN");
        assertThat(byId.get(deleted.getId()).status()).isEqualTo("DELETED");
        // 评论总数是未删口径（与列表页一致）：软删那条不计。
        assertThat(d.commentCount()).isEqualTo(2);
    }

    /** 已下架内容照常可开（复核场景恰恰要看它），状态如实标注。 */
    @Test
    void takenDownPostStillOpensWithStatus() {
        long postId = newPost(newUser().getId(), "已下架的-" + SEQ.incrementAndGet(), List.of());
        var post = posts.findById(postId).orElseThrow();
        post.softDelete();
        posts.save(post);

        var d = detailService.detail(postId, 0, null);
        assertThat(d.post().deleted()).isTrue();

        assertThatThrownBy(() -> detailService.detail(-1L, 0, null))
                .isInstanceOf(AppException.class);
    }

    /** 一级评论分页：每页 20 条（2026-09-02 产品定），第二页装余量。 */
    @Test
    void topLevelCommentsPageBy20() {
        long postId = newPost(newUser().getId(), "分页-" + SEQ.incrementAndGet(), List.of());
        long commenter = newUser().getId();
        for (int i = 0; i < 25; i++) {
            comments.save(Comment.create(postId, null, commenter, "c" + i));
        }

        var page0 = detailService.detail(postId, 0, null);
        assertThat(page0.comments()).hasSize(20);
        assertThat(page0.commentTotalPages()).isEqualTo(2);
        assertThat(page0.commentTotalTopLevel()).isEqualTo(25);
        var page1 = detailService.detail(postId, 1, null);
        assertThat(page1.comments()).hasSize(5);
        // 时间正序：第二页的第一条晚于第一页的最后一条。
        assertThat(page1.comments().get(0).createdAt())
                .isAfterOrEqualTo(page0.comments().get(19).createdAt());
    }

    /** 端点渲染冒烟：超管打开详情页 200，页面带类型名与评论区。 */
    @Test
    void detailPageRendersForSuperAdmin() throws Exception {
        var author = newUser();
        long postId = newPost(author.getId(), "render-" + SEQ.incrementAndGet(), List.of());

        long n = SEQ.incrementAndGet();
        AdminAccount acc = adminAccounts.save(AdminAccount.newSuperAdmin(
                "cd-" + n + "@tailtopia.test", "Detail Admin", "{bcrypt}x"));
        AdminUserDetails principal = new AdminUserDetails(acc.getId(), null, acc.getLarkEmail(),
                acc.getPasswordHash(), AdminAccountType.SUPER_ADMIN);
        Authentication auth = new TestingAuthenticationToken(principal, null,
                new java.util.ArrayList<>(principal.getAuthorities()));

        String html = mvc.perform(get("/admin/content/" + postId).with(authentication(auth)))
                .andReturn().getResponse().getContentAsString();
        assertThat(html).contains("render-");
        assertThat(html).contains(author.getNickname());
    }
}
