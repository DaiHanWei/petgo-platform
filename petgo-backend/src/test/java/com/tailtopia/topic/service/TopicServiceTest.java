package com.tailtopia.topic.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.tailtopia.content.domain.ContentVisibility;
import com.tailtopia.topic.domain.TopicStatus;
import com.tailtopia.topic.domain.TopicType;
import com.tailtopia.topic.service.TopicService.TopicRow;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

/** L0：{@link TopicService#attach}（V1.4.2 social-topic · Story 4.1 · AC4，mock JdbcTemplate）。 */
class TopicServiceTest {

    private static final long POST = 42L;

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final TopicService service = new TopicService(jdbc);

    @SuppressWarnings("unchecked")
    private void existing(String norm, TopicRow row) {
        when(jdbc.query(contains("FROM topics WHERE name_norm"), any(RowMapper.class), eq(norm)))
                .thenReturn(List.of(row));
    }

    @SuppressWarnings("unchecked")
    private void missingThenCreated(String norm, TopicRow row, int insertedRows) {
        when(jdbc.query(contains("FROM topics WHERE name_norm"), any(RowMapper.class), eq(norm)))
                .thenReturn(List.of(), List.of(row));
        when(jdbc.update(contains("INSERT INTO topics"), anyString(), eq(norm))).thenReturn(insertedRows);
    }

    @Test
    void privateOrEmptyTextTouchesNothing() {
        assertThat(service.attach(POST, "#kucing lucu", ContentVisibility.PRIVATE)).isEmpty();
        assertThat(service.attach(POST, null, ContentVisibility.PUBLIC)).isEmpty();
        assertThat(service.attach(POST, "   ", ContentVisibility.PUBLIC)).isEmpty();
        assertThat(service.attach(POST, "tanpa topik", ContentVisibility.PUBLIC)).isEmpty();
        verifyNoInteractions(jdbc);
    }

    @Test
    void newTopicIsCreatedWithFirstSpellingAndLinked() {
        missingThenCreated("kucinglucu", new TopicRow(5L, TopicType.USER, TopicStatus.ACTIVE), 1);

        List<AttachedTopic> out = service.attach(POST, "#KucingLucu dan #kucinglucu", ContentVisibility.PUBLIC);

        assertThat(out).containsExactly(new AttachedTopic(5L, "kucinglucu", false, true));
        ArgumentCaptor<Object> display = ArgumentCaptor.forClass(Object.class);
        verify(jdbc).update(contains("INSERT INTO topics"), display.capture(), eq("kucinglucu"));
        assertThat(display.getValue()).isEqualTo("KucingLucu");
        verify(jdbc).update(contains("INSERT INTO content_topics"), eq(POST), eq(5L));
    }

    @Test
    void lostRaceMeansCreatedFalseButStillLinked() {
        // 并发：对方先提交 → ON CONFLICT DO NOTHING 影响 0 行，再查读到对方那行。
        missingThenCreated("kucing", new TopicRow(9L, TopicType.USER, TopicStatus.ACTIVE), 0);

        assertThat(service.attach(POST, "#kucing", ContentVisibility.PUBLIC))
                .containsExactly(new AttachedTopic(9L, "kucing", false, false));
        verify(jdbc).update(contains("INSERT INTO content_topics"), eq(POST), eq(9L));
    }

    @Test
    void existingTopicKeepsDisplayAndIsNotInserted() {
        existing("kucinglucu", new TopicRow(5L, TopicType.OFFICIAL, TopicStatus.ACTIVE));

        assertThat(service.attach(POST, "#KUCINGLUCU", ContentVisibility.PUBLIC))
                .containsExactly(new AttachedTopic(5L, "kucinglucu", true, false));
        verify(jdbc, never()).update(contains("INSERT INTO topics"), any(), any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void resolvesInNormOrderButLinksAndReturnsInAppearanceOrder() {
        // 防死锁：查 / 插按标准名排序（全局一致的加锁顺序）；关联与返回值仍按正文出现顺序。
        missingThenCreated("bb", new TopicRow(2L, TopicType.USER, TopicStatus.ACTIVE), 1);
        missingThenCreated("aa", new TopicRow(1L, TopicType.USER, TopicStatus.ACTIVE), 1);

        List<AttachedTopic> out = service.attach(POST, "#bb #AA", ContentVisibility.PUBLIC);

        assertThat(out).extracting(AttachedTopic::name).containsExactly("bb", "aa");
        InOrder order = inOrder(jdbc);
        order.verify(jdbc).update(contains("INSERT INTO topics"), anyString(), eq("aa"));
        order.verify(jdbc).update(contains("INSERT INTO topics"), anyString(), eq("bb"));
        order.verify(jdbc).update(contains("INSERT INTO content_topics"), eq(POST), eq(2L));
        order.verify(jdbc).update(contains("INSERT INTO content_topics"), eq(POST), eq(1L));
    }

    @Test
    void disabledIsSkippedHiddenIsLinkedAndNoBackfillOfSixth() {
        existing("a1", new TopicRow(1L, TopicType.USER, TopicStatus.DISABLED));
        existing("b2", new TopicRow(2L, TopicType.USER, TopicStatus.HIDDEN));
        existing("c3", new TopicRow(3L, TopicType.USER, TopicStatus.ACTIVE));
        existing("d4", new TopicRow(4L, TopicType.USER, TopicStatus.ACTIVE));
        existing("e5", new TopicRow(5L, TopicType.USER, TopicStatus.ACTIVE));
        existing("f6", new TopicRow(6L, TopicType.USER, TopicStatus.ACTIVE));

        List<AttachedTopic> out = service.attach(POST, "#a1 #b2 #c3 #d4 #e5 #f6", ContentVisibility.PUBLIC);

        assertThat(out).extracting(AttachedTopic::name).containsExactly("b2", "c3", "d4", "e5");
        verify(jdbc, never()).update(contains("INSERT INTO content_topics"), eq(POST), eq(1L));
        verify(jdbc).update(contains("INSERT INTO content_topics"), eq(POST), eq(2L));
        // 第 6 个不因 DISABLED 让位而补进来（前后端纯文本规则一致）。
        verify(jdbc, never()).update(contains("INSERT INTO content_topics"), eq(POST), eq(6L));
    }
}
