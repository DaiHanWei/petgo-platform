package com.petgo.content.web;

import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.petgo.auth.domain.User;
import com.petgo.content.domain.ContentPost;
import com.petgo.content.domain.ContentType;
import com.petgo.content.repository.ContentPostRepository;
import com.petgo.support.ApiIntegrationTest;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;

/**
 * {@link MeContentController} 集成测试（L1，真 Spring + 安全链 + 落库）。
 *
 * <p>覆盖：GET /me/posts 仅返回当前用户帖子（不含他人）、游标分页、缺 token 401。
 */
class MeContentControllerEndpointTest extends ApiIntegrationTest {

    @Autowired
    private ContentPostRepository posts;

    private ContentPost newPost(long authorId) {
        return posts.save(ContentPost.publish(
                authorId, ContentType.DAILY, null, "测试正文", List.of()));
    }

    @Test
    void myPostsReturnsOnlyCurrentUserPosts() throws Exception {
        User me = newUser();
        User other = newUser();

        long p1 = newPost(me.getId()).getId();
        long p2 = newPost(me.getId()).getId();
        long otherPost = newPost(other.getId()).getId();

        String resp = mvc.perform(get("/api/v1/me/posts")
                        .header(HttpHeaders.AUTHORIZATION, userBearer(me.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isArray())
                .andReturn().getResponse().getContentAsString();

        var items = json.readTree(resp).get("items");
        var ids = new java.util.HashSet<Long>();
        items.forEach(node -> ids.add(node.get("id").asLong()));

        org.assertj.core.api.Assertions.assertThat(ids).contains(p1, p2);
        org.assertj.core.api.Assertions.assertThat(ids).doesNotContain(otherPost);
        // 每条都属于 me。
        items.forEach(node ->
                org.assertj.core.api.Assertions.assertThat(node.get("authorId").asLong())
                        .isEqualTo(me.getId()));
    }

    @Test
    void myPostsSupportsCursorPagination() throws Exception {
        User me = newUser();
        // 造 PAGE_SIZE + 1 条以触发 hasMore / nextCursor。
        int total = com.petgo.content.service.FeedService.PAGE_SIZE + 1;
        for (int i = 0; i < total; i++) {
            newPost(me.getId());
        }

        String firstPage = mvc.perform(get("/api/v1/me/posts")
                        .header(HttpHeaders.AUTHORIZATION, userBearer(me.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hasMore", is(true)))
                .andReturn().getResponse().getContentAsString();

        var firstNode = json.readTree(firstPage);
        org.assertj.core.api.Assertions.assertThat(firstNode.get("items").size())
                .isEqualTo(com.petgo.content.service.FeedService.PAGE_SIZE);
        String cursor = firstNode.get("nextCursor").asText();
        org.assertj.core.api.Assertions.assertThat(cursor).isNotBlank();

        // 翻第二页：剩余 1 条，hasMore=false。
        mvc.perform(get("/api/v1/me/posts")
                        .param("cursor", cursor)
                        .header(HttpHeaders.AUTHORIZATION, userBearer(me.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()", is(1)))
                .andExpect(jsonPath("$.hasMore", is(false)));
    }

    @Test
    void myPostsWithoutTokenReturns401() throws Exception {
        mvc.perform(get("/api/v1/me/posts"))
                .andExpect(status().isUnauthorized());
    }
}
