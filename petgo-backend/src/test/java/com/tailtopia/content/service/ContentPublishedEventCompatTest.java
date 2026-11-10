package com.tailtopia.content.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tailtopia.content.domain.ContentType;
import com.tailtopia.content.domain.ContentVisibility;
import com.tailtopia.content.event.ContentPublishedEvent;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * V1.4.0 multi-pet Story 3.1 · AC4 · L0：{@code ContentPublishedEvent.petIds} 末尾追加与 7 参兼容构造器。
 * V1.4.2 social-topic Story 4.1 · AC6.1：再追加 {@code topicIds} 与 8 参兼容构造器。
 */
class ContentPublishedEventCompatTest {

    private static final Instant NOW = Instant.parse("2026-10-18T03:00:00Z");

    @Test
    void sevenArgConstructorDerivesPetIdsFromPetId() {
        var diary = new ContentPublishedEvent(1L, 2L, ContentType.GROWTH_MOMENT, 9L, 3L,
                ContentVisibility.PUBLIC, NOW);
        assertThat(diary.petIds()).containsExactly(9L);
        assertThat(diary.petId()).isEqualTo(9L);

        var moment = new ContentPublishedEvent(1L, 2L, ContentType.DAILY, null, 0L,
                ContentVisibility.PUBLIC, NOW);
        assertThat(moment.petIds()).isNotNull().isEmpty();
    }

    @Test
    void fullConstructorKeepsOrderAndIsNeverNull() {
        List<Long> source = new ArrayList<>(List.of(5L, 6L));
        var e = new ContentPublishedEvent(1L, 2L, ContentType.GROWTH_MOMENT, 5L, 3L,
                ContentVisibility.PRIVATE, NOW, source);
        source.add(7L); // 防御性拷贝：发布方后续改列表不影响事件
        assertThat(e.petIds()).containsExactly(5L, 6L);
        assertThatThrownBy(() -> e.petIds().add(8L)).isInstanceOf(UnsupportedOperationException.class);

        var nullIds = new ContentPublishedEvent(1L, 2L, ContentType.DAILY, null, 0L,
                ContentVisibility.PUBLIC, NOW, null);
        assertThat(nullIds.petIds()).isNotNull().isEmpty();
    }

    /** 只追加：既有 7 个组件顺序不变，petIds 是第 8 个、topicIds（V1.4.2 Story 4.1）是第 9 个。 */
    @Test
    void petIdsIsAppendedAsLastComponent() {
        var names = java.util.Arrays.stream(ContentPublishedEvent.class.getRecordComponents())
                .map(java.lang.reflect.RecordComponent::getName).toList();
        assertThat(names).containsExactly("postId", "authorId", "type", "petId", "authorGrowthMomentCount",
                "visibility", "createdAt", "petIds", "topicIds");
    }

    @Test
    void sevenAndEightArgConstructorsLeaveTopicIdsEmpty() {
        var seven = new ContentPublishedEvent(1L, 2L, ContentType.DAILY, null, 0L, ContentVisibility.PUBLIC, NOW);
        assertThat(seven.topicIds()).isNotNull().isEmpty();
        var eight = new ContentPublishedEvent(1L, 2L, ContentType.GROWTH_MOMENT, 5L, 1L,
                ContentVisibility.PUBLIC, NOW, List.of(5L));
        assertThat(eight.topicIds()).isNotNull().isEmpty();
        assertThat(eight.petIds()).containsExactly(5L);
    }

    @Test
    void fullConstructorCopiesTopicIdsDefensively() {
        List<Long> source = new ArrayList<>(List.of(11L, 12L));
        var e = new ContentPublishedEvent(1L, 2L, ContentType.DAILY, null, 0L,
                ContentVisibility.PUBLIC, NOW, List.of(), source);
        source.add(13L);
        assertThat(e.topicIds()).containsExactly(11L, 12L);
        assertThatThrownBy(() -> e.topicIds().add(14L)).isInstanceOf(UnsupportedOperationException.class);

        var nullIds = new ContentPublishedEvent(1L, 2L, ContentType.DAILY, null, 0L,
                ContentVisibility.PUBLIC, NOW, null, null);
        assertThat(nullIds.topicIds()).isNotNull().isEmpty();
    }
}
