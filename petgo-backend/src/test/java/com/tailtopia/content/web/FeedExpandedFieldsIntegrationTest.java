package com.tailtopia.content.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tailtopia.auth.domain.User;
import com.tailtopia.content.domain.ContentPost;
import com.tailtopia.content.domain.ContentType;
import com.tailtopia.content.repository.ContentPostRepository;
import com.tailtopia.support.ApiIntegrationTest;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

/**
 * L1：Feed 三项新下发 + 尺寸列（V1.1.6 Story 3.1）。
 *
 * <p>与 {@code FeedBatchAggregationTest}（守查询次数）互补：这里守的是<b>真的下发了、且数字对</b>。
 */
class FeedExpandedFieldsIntegrationTest extends ApiIntegrationTest {

    @Autowired
    private ContentPostRepository posts;

    private ContentPost savePost(long authorId, List<String> images, String text) {
        return posts.save(ContentPost.publish(authorId, ContentType.DAILY, null, text, images, null));
    }

    private String feed(String bearer) throws Exception {
        var req = get("/api/v1/content-posts");
        if (bearer != null) {
            req = req.header(HttpHeaders.AUTHORIZATION, bearer);
        }
        return mvc.perform(req).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    /** 🛡 多图帖下发**整组**图片，不再只有首图（多图轮播必需）。 */
    @Test
    void multiImagePostShipsTheWholeGroupNotJustTheFirst() throws Exception {
        User owner = newUser();
        String text = "multi-" + SEQ.incrementAndGet();
        savePost(owner.getId(), List.of("https://cdn/a.jpg", "https://cdn/b.jpg", "https://cdn/c.jpg"), text);

        String json = feed(null);

        assertThat(json).contains(text);
        assertThat(json)
                .as("整组图片必须下发 —— 只给首图就做不了轮播")
                .contains("https://cdn/b.jpg")
                .contains("https://cdn/c.jpg");
        // 首图字段保留不动（老客户端还在读它）
        assertThat(json).contains("firstImageUrl");
    }

    /** 🛡 未登录访客：已赞恒为 false，且响应结构完整（不因没有登录态而缺字段）。 */
    @Test
    void guestSeesLikedFalseAndCommentCountZero() throws Exception {
        User owner = newUser();
        savePost(owner.getId(), List.of(), "guest-" + SEQ.incrementAndGet());

        String json = feed(null);

        assertThat(json).contains("\"liked\":false");
        assertThat(json).contains("\"commentCount\"");
    }

    /**
     * 🔴 <b>评论数必须与内容详情页是同一个数字。</b>
     *
     * <p>用户从首页点进详情，看到的是同一条内容 —— 数字不一样只会被当成 bug。
     * 这条把两个接口的数字直接比对，任何一边改口径都会红。
     */
    @Test
    void feedCommentCountMatchesTheDetailPage() throws Exception {
        User owner = newUser();
        User commenter = newUser();
        ContentPost post = savePost(owner.getId(), List.of(), "cc-" + SEQ.incrementAndGet());

        for (int i = 0; i < 3; i++) {
            mvc.perform(post("/api/v1/content-posts/" + post.getId() + "/comments")
                            .header(HttpHeaders.AUTHORIZATION, userBearer(commenter.getId()))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"body\":\"halo " + i + "\"}"))
                    .andExpect(status().is2xxSuccessful());
        }

        String detail = mvc.perform(get("/api/v1/content-posts/" + post.getId())
                        .header(HttpHeaders.AUTHORIZATION, userBearer(commenter.getId())))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String feedJson = feed(userBearer(commenter.getId()));

        assertThat(detail).contains("\"commentCount\":3");
        assertThat(feedJson)
                .as("首页与详情页的评论数必须一致 —— 同一个东西两个数字，用户只会以为出 bug")
                .contains("\"commentCount\":3");
    }

    /**
     * 🛡 尺寸与图片<b>同序等长</b>；客户端上报长度不符时<b>整组作废</b>。
     *
     * <p>走真实发布接口，因为「长度校验」正是发布入口的职责。
     */
    @Test
    void reportedSizesAreAlignedAndMismatchedLengthIsDiscarded() throws Exception {
        User owner = newUser();

        // ① 长度对得上 → 采信
        mvc.perform(post("/api/v1/content-posts")
                        .header(HttpHeaders.AUTHORIZATION, userBearer(owner.getId()))
                        .header("Idempotency-Key", "k1-" + SEQ.incrementAndGet())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"type":"DAILY","text":"ok","imageUrls":["https://cdn/x.jpg"],
                                 "imageSizes":[{"w":1200,"h":1600}]}
                                """))
                .andExpect(status().is2xxSuccessful());

        // ② 长度对不上 → 整组作废（两张图只报一个尺寸）
        mvc.perform(post("/api/v1/content-posts")
                        .header(HttpHeaders.AUTHORIZATION, userBearer(owner.getId()))
                        .header("Idempotency-Key", "k2-" + SEQ.incrementAndGet())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"type":"DAILY","text":"mismatch",
                                 "imageUrls":["https://cdn/y.jpg","https://cdn/z.jpg"],
                                 "imageSizes":[{"w":1200,"h":1600}]}
                                """))
                .andExpect(status().is2xxSuccessful());

        var all = posts.findByAuthorIdOrderByCreatedAtDesc(owner.getId());
        var ok = all.stream().filter(p -> "ok".equals(p.getText())).findFirst().orElseThrow();
        var mismatch = all.stream().filter(p -> "mismatch".equals(p.getText())).findFirst().orElseThrow();

        assertThat(ok.getImageSizes()).hasSize(1);
        assertThat(ok.getImageSizes().get(0).w()).isEqualTo(1200);

        assertThat(mismatch.getImageSizes())
                .as("长度不符必须整组作废并保持与图片数等长 —— 部分采信会错位，而错位是图文不符")
                .hasSize(2)
                .containsExactly(null, null);
    }
}
