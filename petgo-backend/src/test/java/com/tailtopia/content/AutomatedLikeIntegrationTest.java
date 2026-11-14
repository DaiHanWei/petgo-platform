package com.tailtopia.content;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tailtopia.auth.domain.User;
import com.tailtopia.content.domain.ContentPost;
import com.tailtopia.content.domain.ContentType;
import com.tailtopia.content.repository.ContentPostRepository;
import com.tailtopia.content.service.LikeService;
import com.tailtopia.support.ApiIntegrationTest;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * L1：V1.5.0 likes Story 1.1 —— {@code content_likes.is_automated} 迁移与 {@link LikeService#likeAutomated}。
 * 需真 PG（云端不跑，本地验收）。
 */
class AutomatedLikeIntegrationTest extends ApiIntegrationTest {

    @Autowired
    private ContentPostRepository posts;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private LikeService likeService;

    private ContentPost savePost(long authorId) {
        return posts.save(ContentPost.publish(authorId, ContentType.DAILY, null, "t", List.of(), null));
    }

    private Boolean automatedOf(long postId, long userId) {
        return jdbc.queryForObject("SELECT is_automated FROM content_likes WHERE post_id = ? AND user_id = ?",
                Boolean.class, postId, userId);
    }

    /** 列默认值 false（存量行在 PG 11+ 带常量默认值加列时即取该默认值，不重写表）。 */
    @Test
    void columnDefaultsToFalseWhenNotGiven() {
        User author = newUser();
        User liker = newUser();
        ContentPost p = savePost(author.getId());
        jdbc.update("INSERT INTO content_likes (post_id, user_id) VALUES (?, ?)", p.getId(), liker.getId());

        assertThat(automatedOf(p.getId(), liker.getId())).isFalse();
    }

    @Test
    void likeAutomatedTwiceKeepsOneRowAndSecondIsNotCreated() {
        User author = newUser();
        User virtual = newUser();
        ContentPost p = savePost(author.getId());

        LikeService.AutomatedLikeResult first = likeService.likeAutomated(p.getId(), virtual.getId(), author.getId());
        LikeService.AutomatedLikeResult second = likeService.likeAutomated(p.getId(), virtual.getId(), author.getId());

        assertThat(first.created()).isTrue();
        assertThat(first.likeCount()).isEqualTo(1L);
        assertThat(second.created()).isFalse();
        assertThat(second.likeCount()).isEqualTo(1L);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM content_likes WHERE post_id = ?", Long.class, p.getId()))
                .isEqualTo(1L);
        assertThat(automatedOf(p.getId(), virtual.getId())).isTrue();
    }

    @Test
    void appLikeEndpointWritesFalse() throws Exception {
        User author = newUser();
        User liker = newUser();
        ContentPost p = savePost(author.getId());

        mvc.perform(post("/api/v1/content-posts/" + p.getId() + "/like")
                        .header(HttpHeaders.AUTHORIZATION, userBearer(liker.getId())))
                .andExpect(status().isOk());

        assertThat(automatedOf(p.getId(), liker.getId())).isFalse();
    }
}
