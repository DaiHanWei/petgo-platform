package com.tailtopia.topic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.tailtopia.content.domain.ContentType;
import com.tailtopia.content.dto.ContentPostCreateRequest;
import com.tailtopia.content.dto.ContentPostResponse;
import com.tailtopia.content.event.ContentPublishedEvent;
import com.tailtopia.content.moderation.ModerationOutcome;
import com.tailtopia.content.service.ContentModerationService;
import com.tailtopia.content.service.ContentService;
import com.tailtopia.content.service.ManualReviewGate;
import com.tailtopia.support.ApiIntegrationTest;
import com.tailtopia.topic.domain.TopicNameRules;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;

/**
 * V1.4.2 social-topic · Story 4.1 · AC7 / AC6.1 · L1（需 postgres + redis；云端不跑，待本地验收）。
 *
 * <p>审核结果用 {@link MockitoBean} 定死：{@code TEXT_BLOCKED} → 422 且不建话题；{@code RISKY} + 人工开关开 → 挂起帖照写关联，
 * {@code approveReview} 后事件 {@code topicIds} 含该话题。
 */
@RecordApplicationEvents
class TopicModerationIntegrationTest extends ApiIntegrationTest {

    @MockitoBean
    private ContentModerationService moderation;
    @MockitoBean
    private ManualReviewGate manualReviewGate;
    @Autowired
    private ContentService contentService;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private ApplicationEvents events;

    @BeforeEach
    void defaults() {
        when(moderation.evaluate(any(), any())).thenReturn(ModerationOutcome.pass(0.0, null));
        when(manualReviewGate.enabled()).thenReturn(false);
    }

    private static String uniq(String base) {
        String s = UUID.randomUUID().toString().replaceAll("[^a-f]", "");
        return base + s.substring(0, Math.min(10, s.length()));
    }

    private int topicRows(String norm) {
        return jdbc.queryForObject("SELECT count(*) FROM topics WHERE name_norm = ?", Integer.class, norm);
    }

    @Test
    void textBlockedCreatesNoTopic() {
        when(moderation.evaluate(any(), any())).thenReturn(ModerationOutcome.textBlocked("abuse"));
        long u = newUser().getId();
        String name = uniq("kasar");
        assertThatThrownBy(() -> contentService.publish(u,
                new ContentPostCreateRequest(ContentType.DAILY, null, "#" + name, null),
                UUID.randomUUID().toString()))
                .isInstanceOf(com.tailtopia.shared.error.AppException.class);
        assertThat(topicRows(TopicNameRules.normalize(name))).isZero();
    }

    @Test
    void riskyPendingPostLinksAndApprovalEventCarriesTopicIds() {
        when(moderation.evaluate(any(), any())).thenReturn(ModerationOutcome.risky(0.9, "abuse"));
        when(manualReviewGate.enabled()).thenReturn(true);
        long u = newUser().getId();
        String name = uniq("ragu");
        String norm = TopicNameRules.normalize(name);

        ContentPostResponse pending = contentService.publish(u,
                new ContentPostCreateRequest(ContentType.DAILY, null, "#" + name, null),
                UUID.randomUUID().toString());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM content_topics WHERE post_id = ?",
                Integer.class, pending.id())).isEqualTo(1);
        assertThat(pending.topics()).extracting(t -> t.name()).containsExactly(norm);
        assertThat(events.stream(ContentPublishedEvent.class).filter(e -> e.postId() == pending.id())).isEmpty();

        contentService.approveReview(pending.id());
        long topicId = jdbc.queryForObject("SELECT id FROM topics WHERE name_norm = ?", Long.class, norm);
        assertThat(events.stream(ContentPublishedEvent.class).filter(e -> e.postId() == pending.id()))
                .singleElement().satisfies(e -> assertThat(e.topicIds()).containsExactly(topicId));
    }
}
