package com.tailtopia.content.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import tools.jackson.databind.JsonNode;
import com.tailtopia.auth.domain.User;
import com.tailtopia.content.domain.ContentPost;
import com.tailtopia.content.domain.ContentType;
import com.tailtopia.content.repository.ContentPostRepository;
import com.tailtopia.support.ApiIntegrationTest;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;

/**
 * L1：Feed 卡与详情的作者 token / 关注关系（V1.3.4 follow · Story 2.1 · AC1.6）—— 断言 JSON 键名
 * {@code authorToken} / {@code authorFollowState.iFollow} / {@code authorFollowState.followsMe}，
 * 覆盖已关注、对方关注我、游客、封号作者（D-8）。
 */
class FeedFollowFieldsIntegrationTest extends ApiIntegrationTest {

    @Autowired
    private ContentPostRepository posts;

    private ContentPost post(User author) {
        return posts.save(ContentPost.publish(author.getId(), ContentType.DAILY, null, "follow-it", List.of()));
    }

    private void follow(User viewer, User target) throws Exception {
        mvc.perform(put("/api/v1/users/" + target.getPublicToken() + "/follow")
                        .header(HttpHeaders.AUTHORIZATION, userBearer(viewer.getId())))
                .andExpect(status().isOk());
    }

    private JsonNode feedItem(String bearer, long postId) throws Exception {
        var req = get("/api/v1/content-posts").param("category", "DAILY");
        if (bearer != null) {
            req = req.header(HttpHeaders.AUTHORIZATION, bearer);
        }
        String body = mvc.perform(req).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        for (JsonNode item : json.readTree(body).get("items")) {
            if (item.get("id").asLong() == postId) {
                return item;
            }
        }
        throw new AssertionError("帖子不在首页第一页：" + postId);
    }

    @Test
    void feedItemCarriesTokenAndFollowState() throws Exception {
        User viewer = newUser();
        User followed = newUser();
        User fan = newUser();
        ContentPost p1 = post(followed);
        ContentPost p2 = post(fan);
        follow(viewer, followed);
        follow(fan, viewer);

        JsonNode a = feedItem(userBearer(viewer.getId()), p1.getId());
        assertThat(a.get("authorToken").asText()).isEqualTo(followed.getPublicToken());
        assertThat(a.get("authorFollowState").get("iFollow").asBoolean()).isTrue();
        assertThat(a.get("authorFollowState").get("followsMe").asBoolean()).isFalse();

        JsonNode b = feedItem(userBearer(viewer.getId()), p2.getId());
        assertThat(b.get("authorFollowState").get("iFollow").asBoolean()).isFalse();
        assertThat(b.get("authorFollowState").get("followsMe").asBoolean()).isTrue();

        JsonNode guest = feedItem(null, p1.getId());
        assertThat(guest.get("authorToken").asText()).isEqualTo(followed.getPublicToken());
        assertThat(guest.get("authorFollowState").get("iFollow").asBoolean()).isFalse();
        assertThat(guest.get("authorFollowState").get("followsMe").asBoolean()).isFalse();
    }

    @Test
    void detailCarriesTokenAndFollowState() throws Exception {
        User viewer = newUser();
        User author = newUser();
        ContentPost p = post(author);
        follow(author, viewer);

        mvc.perform(get("/api/v1/content-posts/" + p.getId())
                        .header(HttpHeaders.AUTHORIZATION, userBearer(viewer.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.authorToken").value(author.getPublicToken()))
                .andExpect(jsonPath("$.authorFollowState.iFollow").value(false))
                .andExpect(jsonPath("$.authorFollowState.followsMe").value(true));

        mvc.perform(get("/api/v1/content-posts/" + p.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.authorFollowState.iFollow").value(false))
                .andExpect(jsonPath("$.authorFollowState.followsMe").value(false));
    }

    /** D-8：封号作者 → 不下发 authorToken（App 不显示关注按钮）。 */
    @Test
    void deactivatedAuthorHasNoToken() throws Exception {
        User viewer = newUser();
        User author = newUser();
        ContentPost p = post(author);
        User banned = users.findById(author.getId()).orElseThrow();
        banned.deactivate();
        users.save(banned);

        mvc.perform(get("/api/v1/content-posts/" + p.getId())
                        .header(HttpHeaders.AUTHORIZATION, userBearer(viewer.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.authorToken").doesNotExist());
    }
}
