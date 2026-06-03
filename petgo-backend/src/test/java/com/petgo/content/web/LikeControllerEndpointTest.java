package com.petgo.content.web;

import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.petgo.auth.domain.User;
import com.petgo.content.domain.ContentPost;
import com.petgo.content.domain.ContentType;
import com.petgo.content.repository.ContentLikeRepository;
import com.petgo.content.repository.ContentPostRepository;
import com.petgo.support.ApiIntegrationTest;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;

/**
 * {@link LikeController} 集成测试（L1，真 Spring + 安全链 + 落库）。
 *
 * <p>覆盖：USER 点赞返回真值并落库、重复点赞幂等、取消点赞、对不存在帖子 404、缺 token 401。
 */
class LikeControllerEndpointTest extends ApiIntegrationTest {

    @Autowired
    private ContentPostRepository posts;

    @Autowired
    private ContentLikeRepository likes;

    private ContentPost newPost(long authorId) {
        return posts.save(ContentPost.publish(
                authorId, ContentType.DAILY, null, "测试正文", List.of()));
    }

    @Test
    void userLikeReturnsLikedTrueAndPersists() throws Exception {
        User author = newUser();
        User actor = newUser();
        ContentPost post = newPost(author.getId());

        mvc.perform(post("/api/v1/content-posts/{id}/like", post.getId())
                        .header(HttpHeaders.AUTHORIZATION, userBearer(actor.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.liked", is(true)))
                .andExpect(jsonPath("$.likeCount", is(1)));

        // 真正落库。
        org.assertj.core.api.Assertions.assertThat(
                likes.existsByPostIdAndUserId(post.getId(), actor.getId())).isTrue();
        org.assertj.core.api.Assertions.assertThat(
                likes.countByPostId(post.getId())).isEqualTo(1L);
    }

    @Test
    void repeatedLikeIsIdempotent() throws Exception {
        User author = newUser();
        User actor = newUser();
        ContentPost post = newPost(author.getId());
        String bearer = userBearer(actor.getId());

        mvc.perform(post("/api/v1/content-posts/{id}/like", post.getId())
                        .header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.likeCount", is(1)));

        // 第二次点赞不叠加。
        mvc.perform(post("/api/v1/content-posts/{id}/like", post.getId())
                        .header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.liked", is(true)))
                .andExpect(jsonPath("$.likeCount", is(1)));

        org.assertj.core.api.Assertions.assertThat(
                likes.countByPostId(post.getId())).isEqualTo(1L);
    }

    @Test
    void unlikeRemovesLikeAndIsIdempotent() throws Exception {
        User author = newUser();
        User actor = newUser();
        ContentPost post = newPost(author.getId());
        String bearer = userBearer(actor.getId());

        mvc.perform(post("/api/v1/content-posts/{id}/like", post.getId())
                .header(HttpHeaders.AUTHORIZATION, bearer));

        mvc.perform(delete("/api/v1/content-posts/{id}/like", post.getId())
                        .header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.liked", is(false)))
                .andExpect(jsonPath("$.likeCount", is(0)));

        org.assertj.core.api.Assertions.assertThat(
                likes.existsByPostIdAndUserId(post.getId(), actor.getId())).isFalse();

        // 未赞再 DELETE 仍成功（幂等）。
        mvc.perform(delete("/api/v1/content-posts/{id}/like", post.getId())
                        .header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.liked", is(false)))
                .andExpect(jsonPath("$.likeCount", is(0)));
    }

    @Test
    void likeMissingPostReturns404() throws Exception {
        User actor = newUser();
        mvc.perform(post("/api/v1/content-posts/{id}/like", 9_999_999L)
                        .header(HttpHeaders.AUTHORIZATION, userBearer(actor.getId())))
                .andExpect(status().isNotFound());
    }

    @Test
    void likeWithoutTokenReturns401() throws Exception {
        User author = newUser();
        ContentPost post = newPost(author.getId());
        mvc.perform(post("/api/v1/content-posts/{id}/like", post.getId()))
                .andExpect(status().isUnauthorized());
    }
}
