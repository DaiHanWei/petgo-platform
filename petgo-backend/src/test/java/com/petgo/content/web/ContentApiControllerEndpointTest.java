package com.petgo.content.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.petgo.auth.domain.User;
import com.petgo.content.domain.ContentPost;
import com.petgo.content.domain.ContentType;
import com.petgo.content.domain.PostStatus;
import com.petgo.content.dto.ContentPostCreateRequest;
import com.petgo.content.repository.ContentPostRepository;
import com.petgo.support.ApiIntegrationTest;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

/**
 * L1 集成：内容发布端点 {@code POST /api/v1/content-posts}（{@link ContentApiController}）。
 *
 * <p>覆盖：USER 正常发布 201 + 落库；缺 token 401 ProblemDetail；非法 body（缺 type / 超长正文 /
 * 超 9 图）422；{@code Idempotency-Key} 幂等（同 key 两次只建一条）。author 取自 JWT sub，不信任客户端。
 */
class ContentApiControllerEndpointTest extends ApiIntegrationTest {

    @Autowired
    private ContentPostRepository posts;

    @Test
    void publishDailyReturns201AndPersists() throws Exception {
        User author = newUser();
        var req = new ContentPostCreateRequest(
                ContentType.DAILY, null, "今天带狗子去公园", List.of("https://cdn.petgo.test/a.jpg"));

        var result = mvc.perform(post("/api/v1/content-posts")
                        .header("Authorization", userBearer(author.getId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.type").value("DAILY"))
                .andExpect(jsonPath("$.text").value("今天带狗子去公园"))
                .andExpect(jsonPath("$.imageUrls[0]").value("https://cdn.petgo.test/a.jpg"))
                .andReturn();

        long id = json.readTree(result.getResponse().getContentAsString()).get("id").asLong();
        ContentPost saved = posts.findById(id).orElseThrow();
        // author 取自 JWT，不来自 body。
        org.assertj.core.api.Assertions.assertThat(saved.getAuthorId()).isEqualTo(author.getId());
        org.assertj.core.api.Assertions.assertThat(saved.getType()).isEqualTo(ContentType.DAILY);
        org.assertj.core.api.Assertions.assertThat(saved.getStatus()).isEqualTo(PostStatus.PUBLISHED);
        org.assertj.core.api.Assertions.assertThat(saved.getDeletedAt()).isNull();
    }

    @Test
    void missingTokenReturns401ProblemDetail() throws Exception {
        var req = new ContentPostCreateRequest(ContentType.DAILY, null, "hi", null);

        mvc.perform(post("/api/v1/content-posts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(req)))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("Content-Type",
                        org.hamcrest.Matchers.containsString("application/problem+json")))
                .andExpect(jsonPath("$.status").value(401));
    }

    @Test
    void missingTypeReturns422() throws Exception {
        User author = newUser();
        // type 缺失（@NotNull）→ Bean 校验失败。
        String body = "{\"text\":\"hi\"}";

        mvc.perform(post("/api/v1/content-posts")
                        .header("Authorization", userBearer(author.getId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.status").value(422));
    }

    @Test
    void tooLongTextReturns422() throws Exception {
        User author = newUser();
        String tooLong = "字".repeat(1001); // @Size(max=1000)
        var req = new ContentPostCreateRequest(ContentType.DAILY, null, tooLong, null);

        mvc.perform(post("/api/v1/content-posts")
                        .header("Authorization", userBearer(author.getId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(req)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.status").value(422));
    }

    @Test
    void tooManyImagesReturns422() throws Exception {
        User author = newUser();
        List<String> imgs = java.util.stream.IntStream.range(0, 10)
                .mapToObj(i -> "https://cdn.petgo.test/" + i + ".jpg")
                .toList(); // @Size(max=9)
        var req = new ContentPostCreateRequest(ContentType.DAILY, null, "hi", imgs);

        mvc.perform(post("/api/v1/content-posts")
                        .header("Authorization", userBearer(author.getId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(req)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.status").value(422));
    }

    @Test
    void idempotencyKeyDedupesToSinglePost() throws Exception {
        User author = newUser();
        String key = "idem-" + SEQ.incrementAndGet();
        var req = new ContentPostCreateRequest(ContentType.KNOWLEDGE, null, "科普一则", null);
        String payload = json.writeValueAsString(req);

        var first = mvc.perform(post("/api/v1/content-posts")
                        .header("Authorization", userBearer(author.getId()))
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isCreated())
                .andReturn();
        long firstId = json.readTree(first.getResponse().getContentAsString()).get("id").asLong();

        var second = mvc.perform(post("/api/v1/content-posts")
                        .header("Authorization", userBearer(author.getId()))
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isCreated())
                .andReturn();
        long secondId = json.readTree(second.getResponse().getContentAsString()).get("id").asLong();

        // 同 key 重放 → 取回既有资源，不重复落库。
        org.assertj.core.api.Assertions.assertThat(secondId).isEqualTo(firstId);
        long count = posts.findMyPosts(author.getId(), false, null, null,
                        org.springframework.data.domain.PageRequest.of(0, 50))
                .stream().filter(p -> p.getType() == ContentType.KNOWLEDGE).count();
        org.assertj.core.api.Assertions.assertThat(count).isEqualTo(1L);
    }
}
