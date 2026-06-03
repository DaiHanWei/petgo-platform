package com.petgo.content.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.petgo.auth.domain.User;
import com.petgo.content.domain.Comment;
import com.petgo.content.domain.ContentPost;
import com.petgo.content.domain.ContentType;
import com.petgo.content.repository.CommentRepository;
import com.petgo.content.repository.ContentPostRepository;
import com.petgo.support.ApiIntegrationTest;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * L1 集成：内容详情 + 评论只读端点（{@link ContentDetailController}）。
 *
 * <p>覆盖：存在的 post 200 + 字段；不存在 / 已软删 → 统一 404 文案；评论列表（一级 + 内嵌二级 + replyCount）；
 * 回复展开列表；游客可读；isAuthor（作者 token vs 游客）；注销/缺失作者匿名化（200 非 404）。
 */
class ContentDetailControllerEndpointTest extends ApiIntegrationTest {

    @Autowired
    private ContentPostRepository posts;

    @Autowired
    private CommentRepository comments;

    private ContentPost savePost(long authorId, String text) {
        return posts.save(ContentPost.publish(authorId, ContentType.DAILY, null, text, List.of()));
    }

    /** 软删用户（set deleted_at）——FK 强约束下「注销作者」只能这样造，不能用悬空 author_id。 */
    private void softDelete(User u) {
        try {
            var f = User.class.getDeclaredField("deletedAt");
            f.setAccessible(true);
            f.set(u, Instant.now());
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
        users.save(u);
    }

    @Test
    void detailOfExistingPostReturns200WithFields() throws Exception {
        User author = newUser();
        ContentPost post = savePost(author.getId(), "详情内容");

        // 游客视角：可读，isAuthor=false，liked=false。
        mvc.perform(get("/api/v1/content-posts/" + post.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(post.getId().intValue()))
                .andExpect(jsonPath("$.body").value("详情内容"))
                .andExpect(jsonPath("$.type").value("DAILY"))
                .andExpect(jsonPath("$.authorId").value(author.getId().intValue()))
                .andExpect(jsonPath("$.authorDeleted").value(false))
                .andExpect(jsonPath("$.isAuthor").value(false))
                .andExpect(jsonPath("$.liked").value(false))
                .andExpect(jsonPath("$.commentCount").value(0));
    }

    @Test
    void detailIsAuthorTrueForOwnerToken() throws Exception {
        User author = newUser();
        ContentPost post = savePost(author.getId(), "我的帖");

        mvc.perform(get("/api/v1/content-posts/" + post.getId())
                        .header("Authorization", userBearer(author.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.isAuthor").value(true));
    }

    @Test
    void nonexistentPostReturns404() throws Exception {
        long ghost = 980_000_000L + SEQ.incrementAndGet();
        mvc.perform(get("/api/v1/content-posts/" + ghost))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.detail").value("这条内容已不存在"));
    }

    @Test
    void softDeletedPostReturns404() throws Exception {
        User author = newUser();
        ContentPost post = savePost(author.getId(), "将被删除");
        post.softDelete();
        posts.save(post);

        // 已软删 → 统一 404 文案（防枚举），非泄漏曾存在。
        mvc.perform(get("/api/v1/content-posts/" + post.getId()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("这条内容已不存在"));
    }

    @Test
    void deletedAuthorStillReturns200Anonymized() throws Exception {
        // 注销作者：造真实用户发帖后软删 → 详情仍 200，作者匿名化（NFR-8），非 404。
        User author = newUser();
        long authorId = author.getId();
        ContentPost post = savePost(authorId, "注销作者的帖");
        softDelete(author);

        mvc.perform(get("/api/v1/content-posts/" + post.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.authorDeleted").value(true))
                .andExpect(jsonPath("$.authorId").value(authorId))
                // NON_NULL：匿名化后昵称/头像缺省（不外泄）。
                .andExpect(jsonPath("$.authorNickname").doesNotExist())
                .andExpect(jsonPath("$.authorAvatarUrl").doesNotExist());
    }

    @Test
    void commentsListReturnsTopLevelWithInlineReplies() throws Exception {
        User author = newUser();
        User commenter = newUser();
        ContentPost post = savePost(author.getId(), "带评论的帖");

        Comment top = comments.save(Comment.create(post.getId(), null, commenter.getId(), "一级评论"));
        comments.save(Comment.create(post.getId(), top.getId(), commenter.getId(), "回复1"));
        comments.save(Comment.create(post.getId(), top.getId(), commenter.getId(), "回复2"));

        // 游客可读评论列表。
        mvc.perform(get("/api/v1/content-posts/" + post.getId() + "/comments"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isArray())
                .andExpect(jsonPath("$.items[?(@.body=='一级评论')].replyCount").value(
                        org.hamcrest.Matchers.contains(2)))
                .andExpect(jsonPath(
                        "$.items[?(@.body=='一级评论')].replies[?(@.body=='回复1')]").exists());
    }

    @Test
    void repliesListReturnsSecondLevel() throws Exception {
        User author = newUser();
        User commenter = newUser();
        ContentPost post = savePost(author.getId(), "回复展开帖");
        Comment top = comments.save(Comment.create(post.getId(), null, commenter.getId(), "父评论"));
        comments.save(Comment.create(post.getId(), top.getId(), commenter.getId(), "二级A"));
        comments.save(Comment.create(post.getId(), top.getId(), commenter.getId(), "二级B"));

        // 游客可读回复列表（按父展开）。
        mvc.perform(get("/api/v1/comments/" + top.getId() + "/replies"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].body").value("二级A"))
                .andExpect(jsonPath("$.items[1].body").value("二级B"))
                // 二级回复的 replyCount/replies 为 null（NON_NULL 缺省）。
                .andExpect(jsonPath("$.items[0].replyCount").doesNotExist());
    }

    @Test
    void commentsOfNonexistentPostReturns404() throws Exception {
        long ghost = 960_000_000L + SEQ.incrementAndGet();
        mvc.perform(get("/api/v1/content-posts/" + ghost + "/comments"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("这条内容已不存在"));
    }
}
