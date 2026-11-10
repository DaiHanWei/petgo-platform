package com.tailtopia.topic.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.tailtopia.shared.error.AppException;
import com.tailtopia.topic.domain.Topic;
import com.tailtopia.topic.domain.TopicType;
import com.tailtopia.topic.repository.TopicRepository;
import com.tailtopia.topic.web.dto.TopicDetailResponse;
import com.tailtopia.topic.web.dto.TopicSummaryResponse;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * L0 · V1.4.2 social-topic Story 4.2 · AC0 / AC1 / AC3 ~ AC5（mock 仓储）。
 */
class TopicQueryServiceTest {

    private TopicRepository topics;
    private TopicQueryService service;

    @BeforeEach
    void setUp() {
        topics = mock(TopicRepository.class);
        service = new TopicQueryService(mock(NamedParameterJdbcTemplate.class), topics);
    }

    private static Topic topic(long id, String norm, String display, TopicType type, String descId,
            String descEn) {
        Topic t = mock(Topic.class);
        when(t.getId()).thenReturn(id);
        when(t.getNameNorm()).thenReturn(norm);
        when(t.getNameDisplay()).thenReturn(display);
        when(t.getType()).thenReturn(type);
        when(t.isOfficial()).thenReturn(type == TopicType.OFFICIAL);
        when(t.getDescId()).thenReturn(descId);
        when(t.getDescEn()).thenReturn(descEn);
        when(t.getHeatScore()).thenReturn(BigDecimal.ONE);
        return t;
    }

    // ---------------------------------------------------------------- AC0

    @Test
    void pathNameIsNormalizedBeforeLookup() {
        Topic t = topic(7, "kucinglucu", "KucingLucu", TopicType.USER, null, null);
        when(topics.findByNameNormAndStatusIn(eq("kucinglucu"), any())).thenReturn(Optional.of(t));

        assertThat(service.requireVisible("KucingLucu")).isSameAs(t);
        assertThat(service.requireVisible("kucinglucu")).isSameAs(t);
        verify(topics, org.mockito.Mockito.times(2))
                .findByNameNormAndStatusIn(eq("kucinglucu"), eq(TopicQueryService.VISIBLE));
    }

    @Test
    void visibleMeansActiveOrHidden() {
        assertThat(TopicQueryService.VISIBLE).containsExactlyInAnyOrder(
                com.tailtopia.topic.domain.TopicStatus.ACTIVE, com.tailtopia.topic.domain.TopicStatus.HIDDEN);
    }

    @Test
    void invalidNameIs404WithoutQuery() {
        for (String bad : List.of("a", "kucing lucu", "1234", "x".repeat(31), "", "kucing-lucu")) {
            assertThatThrownBy(() -> service.requireVisible(bad))
                    .isInstanceOf(AppException.class)
                    .satisfies(e -> assertThat(((AppException) e).getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
        }
        verifyNoInteractions(topics);
    }

    @Test
    void disabledAndMissingAreSame404() {
        // 仓储只按 ACTIVE / HIDDEN 查：DISABLED 与不存在都是 empty → 同一个异常、同一个 detail
        when(topics.findByNameNormAndStatusIn(anyString(), any())).thenReturn(Optional.empty());
        AppException a = org.junit.jupiter.api.Assertions.assertThrows(AppException.class,
                () -> service.requireVisible("disabledtopic"));
        AppException b = org.junit.jupiter.api.Assertions.assertThrows(AppException.class,
                () -> service.detail("notexist", null));
        assertThat(a.getStatus()).isEqualTo(HttpStatus.NOT_FOUND).isEqualTo(b.getStatus());
        assertThat(a.getMessage()).isEqualTo(b.getMessage());
    }

    // ---------------------------------------------------------------- AC1

    @Test
    void descriptionPicksByAcceptLanguageWithFallback() {
        Topic both = topic(1, "resmi", "Resmi", TopicType.OFFICIAL, "Deskripsi", "Description");
        when(topics.findByNameNormAndStatusIn(eq("resmi"), any())).thenReturn(Optional.of(both));

        assertThat(service.detail("resmi", "id-ID").description()).isEqualTo("Deskripsi");
        assertThat(service.detail("resmi", "id").description()).isEqualTo("Deskripsi");
        assertThat(service.detail("resmi", "en-US").description()).isEqualTo("Description");
        assertThat(service.detail("resmi", null).description()).isEqualTo("Description");

        Topic enOnly = topic(2, "enonly", "EnOnly", TopicType.OFFICIAL, null, "Only EN");
        when(topics.findByNameNormAndStatusIn(eq("enonly"), any())).thenReturn(Optional.of(enOnly));
        assertThat(service.detail("enonly", "id-ID").description()).isEqualTo("Only EN");

        Topic idOnly = topic(3, "idonly", "IdOnly", TopicType.OFFICIAL, "Hanya ID", "  ");
        when(topics.findByNameNormAndStatusIn(eq("idonly"), any())).thenReturn(Optional.of(idOnly));
        assertThat(service.detail("idonly", null).description()).isEqualTo("Hanya ID");

        Topic none = topic(4, "nodesc", "NoDesc", TopicType.OFFICIAL, null, null);
        when(topics.findByNameNormAndStatusIn(eq("nodesc"), any())).thenReturn(Optional.of(none));
        assertThat(service.detail("nodesc", "id-ID").description()).isNull();
    }

    @Test
    void userTopicHasNoDescriptionAndExposesNoId() {
        Topic user = topic(99, "anabul", "Anabul", TopicType.USER, "x", "y");
        when(topics.findByNameNormAndStatusIn(eq("anabul"), any())).thenReturn(Optional.of(user));

        TopicDetailResponse r = service.detail("Anabul", "id-ID");
        assertThat(r.name()).isEqualTo("anabul");
        assertThat(r.displayName()).isEqualTo("Anabul");
        assertThat(r.official()).isFalse();
        assertThat(r.description()).isNull();
        // record 组件里没有 id / postCount
        assertThat(java.util.Arrays.stream(TopicDetailResponse.class.getRecordComponents())
                .map(java.lang.reflect.RecordComponent::getName))
                .containsExactly("name", "displayName", "official", "description");
    }

    // ---------------------------------------------------------------- AC3

    @Test
    void suggestWithoutQueryReturnsTop20() {
        Topic off = topic(1, "resmi", "Resmi", TopicType.OFFICIAL, null, null);
        when(topics.findSuggestions(any(Pageable.class))).thenReturn(List.of(off));

        List<TopicSummaryResponse> r = service.suggest(null);
        assertThat(r).extracting(TopicSummaryResponse::name).containsExactly("resmi");
        assertThat(r.get(0).featured()).isNull();
        ArgumentCaptor<Pageable> page = ArgumentCaptor.forClass(Pageable.class);
        verify(topics).findSuggestions(page.capture());
        assertThat(page.getValue().getPageSize()).isEqualTo(20);

        service.suggest("   ");
        verify(topics, org.mockito.Mockito.times(2)).findSuggestions(any(Pageable.class));
        verify(topics, never()).findSuggestionsByPrefix(anyString(), anyInt());
    }

    @Test
    void suggestInvalidQueryIsEmptyWithoutQuery() {
        assertThat(service.suggest("kucing lucu")).isEmpty();
        assertThat(service.suggest("x".repeat(31))).isEmpty();
        assertThat(service.suggest("kuc%")).isEmpty();
        assertThat(service.suggest("#kucing")).isEmpty();
        verifyNoInteractions(topics);
    }

    @Test
    void suggestPrefixIsNormalizedAndEscaped() {
        when(topics.findSuggestionsByPrefix(anyString(), anyInt())).thenReturn(List.of());

        service.suggest("Kucing_L");
        verify(topics).findSuggestionsByPrefix("kucing\\_l%", 20);

        // 1 个字也查
        service.suggest("K");
        verify(topics).findSuggestionsByPrefix("k%", 20);
    }

    @Test
    void likePrefixEscapesAllWildcards() {
        assertThat(TopicQueryService.likePrefix("a_b%c\\d")).isEqualTo("a\\_b\\%c\\\\d%");
    }

    // ---------------------------------------------------------------- AC4

    @Test
    void homeRowFeaturedFirstThenHeatFill() {
        Topic f1 = topic(1, "satu", "Satu", TopicType.OFFICIAL, null, null);
        Topic f2 = topic(2, "dua", "Dua", TopicType.USER, null, null);
        Topic h1 = topic(3, "tiga", "Tiga", TopicType.USER, null, null);
        when(topics.findFeatured()).thenReturn(List.of(f1, f2));
        when(topics.findHotUnfeatured(any(Pageable.class))).thenReturn(List.of(h1));

        List<TopicSummaryResponse> r = service.homeRow();
        assertThat(r).extracting(TopicSummaryResponse::name).containsExactly("satu", "dua", "tiga");
        assertThat(r).extracting(TopicSummaryResponse::featured).containsExactly(true, true, false);
        assertThat(r).extracting(TopicSummaryResponse::official).containsExactly(true, false, false);
        ArgumentCaptor<Pageable> page = ArgumentCaptor.forClass(Pageable.class);
        verify(topics).findHotUnfeatured(page.capture());
        assertThat(page.getValue().getPageSize()).isEqualTo(3);
    }

    @Test
    void homeRowFullFeaturedSkipsFill() {
        List<Topic> five = java.util.stream.IntStream.rangeClosed(1, 5)
                .mapToObj(i -> topic(i, "topik" + (char) ('a' + i), "T", TopicType.USER, null, null))
                .toList();
        when(topics.findFeatured()).thenReturn(five);

        assertThat(service.homeRow()).hasSize(5);
        verify(topics, never()).findHotUnfeatured(any(Pageable.class));
    }

    @Test
    void homeRowEmpty() {
        when(topics.findFeatured()).thenReturn(List.of());
        when(topics.findHotUnfeatured(any(Pageable.class))).thenReturn(List.of());
        assertThat(service.homeRow()).isEmpty();
    }

    // ---------------------------------------------------------------- AC5

    @Test
    void relatedExcludesCurrentAndLimits10() {
        Topic cur = topic(5, "anabul", "Anabul", TopicType.USER, null, null);
        Topic other = topic(6, "kucing", "Kucing", TopicType.OFFICIAL, null, null);
        when(topics.findByNameNormAndStatusIn(eq("anabul"), any())).thenReturn(Optional.of(cur));
        when(topics.findRelated(anyLong(), any(Pageable.class))).thenReturn(List.of(other));

        assertThat(service.related("anabul")).extracting(TopicSummaryResponse::name).containsExactly("kucing");
        ArgumentCaptor<Pageable> page = ArgumentCaptor.forClass(Pageable.class);
        verify(topics).findRelated(eq(5L), page.capture());
        assertThat(page.getValue().getPageSize()).isEqualTo(10);
    }

    @Test
    void relatedOfInaccessibleTopicIs404() {
        when(topics.findByNameNormAndStatusIn(anyString(), any())).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.related("gone")).isInstanceOf(AppException.class);
        verify(topics, never()).findRelated(anyLong(), any(Pageable.class));
    }
}
