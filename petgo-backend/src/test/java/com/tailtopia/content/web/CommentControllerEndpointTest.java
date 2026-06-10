package com.tailtopia.content.web;

import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tailtopia.auth.domain.User;
import com.tailtopia.content.domain.ContentPost;
import com.tailtopia.content.domain.ContentType;
import com.tailtopia.content.repository.CommentRepository;
import com.tailtopia.content.repository.ContentPostRepository;
import com.tailtopia.support.ApiIntegrationTest;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

/**
 * {@link CommentController} 集成测试（L1，真 Spring + 安全链 + 落库 + Bean 校验）。
 *
 * <p>覆盖：发一级评论 201 + 落库、回复（parentId）、空内容/超长 422、删除自己评论 204、
 * 删他人评论 403、缺 token 401。
 */
class CommentControllerEndpointTest extends ApiIntegrationTest {

    @Autowired
    private ContentPostRepository posts;

    @Autowired
    private CommentRepository comments;

    private ContentPost newPost(long authorId) {
        return posts.save(ContentPost.publish(
                authorId, ContentType.DAILY, null, "测试正文", List.of()));
    }

    private String body(String text) {
        return "{\"body\":\"" + text + "\"}";
    }

    @Test
    void createTopLevelCommentReturns201AndPersists() throws Exception {
        User author = newUser();
        User actor = newUser();
        ContentPost post = newPost(author.getId());

        String resp = mvc.perform(post("/api/v1/content-posts/{postId}/comments", post.getId())
                        .header(HttpHeaders.AUTHORIZATION, userBearer(actor.getId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("第一条评论")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id", notNullValue()))
                .andExpect(jsonPath("$.body", is("第一条评论")))
                .andExpect(jsonPath("$.replyCount", is(0)))
                .andReturn().getResponse().getContentAsString();

        org.assertj.core.api.Assertions.assertThat(
                json.readTree(resp).get("authorId").asLong()).isEqualTo(actor.getId());

        org.assertj.core.api.Assertions.assertThat(
                comments.countByPostIdAndDeletedAtIsNull(post.getId())).isEqualTo(1L);
    }

    @Test
    void replyToCommentReturns201AndLinksParent() throws Exception {
        User author = newUser();
        User commenter = newUser();
        User replier = newUser();
        ContentPost post = newPost(author.getId());

        String topJson = mvc.perform(post("/api/v1/content-posts/{postId}/comments", post.getId())
                        .header(HttpHeaders.AUTHORIZATION, userBearer(commenter.getId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("一级评论")))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long parentId = json.readTree(topJson).get("id").asLong();

        mvc.perform(post("/api/v1/comments/{parentId}/replies", parentId)
                        .header(HttpHeaders.AUTHORIZATION, userBearer(replier.getId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("二级回复")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id", notNullValue()))
                .andExpect(jsonPath("$.body", is("二级回复")))
                // 二级回复的 replyCount/replies 为 null（NON_NULL 省略）。
                .andExpect(jsonPath("$.replyCount").doesNotExist());

        // 一级 + 二级共两条。
        org.assertj.core.api.Assertions.assertThat(
                comments.countByPostIdAndDeletedAtIsNull(post.getId())).isEqualTo(2L);
    }

    @Test
    void emptyBodyReturns422() throws Exception {
        User author = newUser();
        User actor = newUser();
        ContentPost post = newPost(author.getId());

        mvc.perform(post("/api/v1/content-posts/{postId}/comments", post.getId())
                        .header(HttpHeaders.AUTHORIZATION, userBearer(actor.getId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("")))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void tooLongBodyReturns422() throws Exception {
        User author = newUser();
        User actor = newUser();
        ContentPost post = newPost(author.getId());
        String tooLong = "啊".repeat(201);

        mvc.perform(post("/api/v1/content-posts/{postId}/comments", post.getId())
                        .header(HttpHeaders.AUTHORIZATION, userBearer(actor.getId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(tooLong)))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void deleteOwnCommentReturns204() throws Exception {
        User author = newUser();
        User actor = newUser();
        ContentPost post = newPost(author.getId());

        String topJson = mvc.perform(post("/api/v1/content-posts/{postId}/comments", post.getId())
                        .header(HttpHeaders.AUTHORIZATION, userBearer(actor.getId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("待删除评论")))
                .andReturn().getResponse().getContentAsString();
        long commentId = json.readTree(topJson).get("id").asLong();

        mvc.perform(delete("/api/v1/comments/{id}", commentId)
                        .header(HttpHeaders.AUTHORIZATION, userBearer(actor.getId())))
                .andExpect(status().isNoContent());

        // 软删后未删计数归零。
        org.assertj.core.api.Assertions.assertThat(
                comments.countByPostIdAndDeletedAtIsNull(post.getId())).isEqualTo(0L);
    }

    @Test
    void deleteOthersCommentReturns403() throws Exception {
        User author = newUser();
        User commenter = newUser();
        User stranger = newUser();
        ContentPost post = newPost(author.getId());

        String topJson = mvc.perform(post("/api/v1/content-posts/{postId}/comments", post.getId())
                        .header(HttpHeaders.AUTHORIZATION, userBearer(commenter.getId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("别人的评论")))
                .andReturn().getResponse().getContentAsString();
        long commentId = json.readTree(topJson).get("id").asLong();

        // 既非评论作者、也非内容作者 → 403。
        mvc.perform(delete("/api/v1/comments/{id}", commentId)
                        .header(HttpHeaders.AUTHORIZATION, userBearer(stranger.getId())))
                .andExpect(status().isForbidden());

        // 仍未被删除。
        org.assertj.core.api.Assertions.assertThat(
                comments.countByPostIdAndDeletedAtIsNull(post.getId())).isEqualTo(1L);
    }

    @Test
    void commentMissingPostReturns404() throws Exception {
        User actor = newUser();
        mvc.perform(post("/api/v1/content-posts/{postId}/comments", 9_999_999L)
                        .header(HttpHeaders.AUTHORIZATION, userBearer(actor.getId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("评论不存在的帖子")))
                .andExpect(status().isNotFound());
    }

    @Test
    void commentWithoutTokenReturns401() throws Exception {
        User author = newUser();
        ContentPost post = newPost(author.getId());
        mvc.perform(post("/api/v1/content-posts/{postId}/comments", post.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("匿名评论")))
                .andExpect(status().isUnauthorized());
    }
}
