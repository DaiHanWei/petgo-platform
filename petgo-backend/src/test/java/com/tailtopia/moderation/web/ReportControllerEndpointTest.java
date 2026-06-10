package com.tailtopia.moderation.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tailtopia.auth.domain.User;
import com.tailtopia.content.domain.ContentPost;
import com.tailtopia.content.domain.ContentType;
import com.tailtopia.content.repository.ContentPostRepository;
import com.tailtopia.moderation.domain.ContentReport;
import com.tailtopia.moderation.domain.ReportReason;
import com.tailtopia.moderation.domain.ReportStatus;
import com.tailtopia.moderation.repository.ContentReportRepository;
import com.tailtopia.support.ApiIntegrationTest;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

/**
 * {@link ReportController} 集成测试（L1，真 Spring + 安全链 + 落库）。
 *
 * <p>{@code POST /api/v1/content-posts/{postId}/reports}：需 JWT，写工单 status=PENDING，
 * <b>不触发任何自动下架</b>。覆盖：USER 举报→202 + 落库 PENDING；重复举报幂等（不叠加）；
 * 对不存在/已删内容→404；非法 body（缺 reasonType）→422；缺 token→401。
 */
class ReportControllerEndpointTest extends ApiIntegrationTest {

    @Autowired
    private ContentPostRepository posts;

    @Autowired
    private ContentReportRepository reports;

    private ContentPost newPost(long authorId) {
        return posts.save(ContentPost.publish(
                authorId, ContentType.DAILY, null, "被举报的正文", List.of()));
    }

    @Test
    void userReportsContentReturns202AndPersistsPending() throws Exception {
        User author = newUser();
        User reporter = newUser();
        ContentPost post = newPost(author.getId());

        mvc.perform(post("/api/v1/content-posts/{postId}/reports", post.getId())
                        .header(HttpHeaders.AUTHORIZATION, userBearer(reporter.getId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reasonType\":\"INAPPROPRIATE\"}"))
                .andExpect(status().isAccepted());

        List<ContentReport> queue = reports.findByStatusOrderByCreatedAtDesc(
                ReportStatus.PENDING, org.springframework.data.domain.PageRequest.of(0, 50));
        ContentReport saved = queue.stream()
                .filter(r -> r.getPostId().equals(post.getId())
                        && r.getReporterId().equals(reporter.getId()))
                .findFirst()
                .orElseThrow();
        assertThat(saved.getStatus()).isEqualTo(ReportStatus.PENDING);
        assertThat(saved.getReasonType()).isEqualTo(ReportReason.INAPPROPRIATE);
        // 举报不下架：内容仍可见。
        assertThat(posts.findById(post.getId()).orElseThrow().getDeletedAt()).isNull();
    }

    @Test
    void repeatedReportBySameUserIsIdempotent() throws Exception {
        User author = newUser();
        User reporter = newUser();
        ContentPost post = newPost(author.getId());
        String bearer = userBearer(reporter.getId());

        mvc.perform(post("/api/v1/content-posts/{postId}/reports", post.getId())
                        .header(HttpHeaders.AUTHORIZATION, bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reasonType\":\"HARASSMENT\"}"))
                .andExpect(status().isAccepted());

        // 同 reporter 同 post 再举报：幂等成功，不叠加第二条。
        mvc.perform(post("/api/v1/content-posts/{postId}/reports", post.getId())
                        .header(HttpHeaders.AUTHORIZATION, bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reasonType\":\"OTHER\"}"))
                .andExpect(status().isAccepted());

        assertThat(reports.countByPostId(post.getId())).isEqualTo(1L);
    }

    @Test
    void reportMissingContentReturns404() throws Exception {
        User reporter = newUser();

        mvc.perform(post("/api/v1/content-posts/{postId}/reports", 9_999_999L)
                        .header(HttpHeaders.AUTHORIZATION, userBearer(reporter.getId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reasonType\":\"ILLEGAL\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void reportDeletedContentReturns404() throws Exception {
        User author = newUser();
        User reporter = newUser();
        ContentPost post = newPost(author.getId());
        post.softDelete();
        posts.save(post);

        mvc.perform(post("/api/v1/content-posts/{postId}/reports", post.getId())
                        .header(HttpHeaders.AUTHORIZATION, userBearer(reporter.getId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reasonType\":\"ILLEGAL\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void missingReasonTypeReturns422() throws Exception {
        User author = newUser();
        User reporter = newUser();
        ContentPost post = newPost(author.getId());

        mvc.perform(post("/api/v1/content-posts/{postId}/reports", post.getId())
                        .header(HttpHeaders.AUTHORIZATION, userBearer(reporter.getId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void missingTokenReturns401() throws Exception {
        User author = newUser();
        ContentPost post = newPost(author.getId());

        mvc.perform(post("/api/v1/content-posts/{postId}/reports", post.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reasonType\":\"ILLEGAL\"}"))
                .andExpect(status().isUnauthorized());
    }
}
