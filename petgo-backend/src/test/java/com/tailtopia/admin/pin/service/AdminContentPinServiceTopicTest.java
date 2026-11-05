package com.tailtopia.admin.pin.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tailtopia.admin.audit.service.AdminAuditService;
import com.tailtopia.admin.pin.dto.PinRow;
import com.tailtopia.admin.pin.dto.TopicPinPreview;
import com.tailtopia.admin.pin.dto.TopicPinRow;
import com.tailtopia.admin.topic.service.AdminTopicStore;
import com.tailtopia.admin.topic.service.TopicView;
import com.tailtopia.auth.service.AccountQueryService;
import com.tailtopia.content.domain.ContentPin;
import com.tailtopia.content.domain.ContentPost;
import com.tailtopia.content.domain.ContentType;
import com.tailtopia.content.domain.ContentVisibility;
import com.tailtopia.content.repository.ContentPostRepository;
import com.tailtopia.content.service.ContentPinService;
import com.tailtopia.shared.error.AppException;
import com.tailtopia.shared.schedule.ScheduleWindow;
import com.tailtopia.topic.domain.TopicStatus;
import com.tailtopia.topic.domain.TopicType;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * L0（V1.4.2 social-topic · Story 6.2）：话题页坑位解析、话题内容校验、G1a 汇总、话题抽屉区块（全 mock，无 Spring 上下文）。
 */
class AdminContentPinServiceTopicTest {

    private static final Instant NOW = Instant.parse("2026-11-20T05:00:00Z");
    private static final long ADMIN = 9L;

    private final ContentPinService pins = mock(ContentPinService.class);
    private final ContentPostRepository posts = mock(ContentPostRepository.class);
    private final AdminAuditService audit = mock(AdminAuditService.class);
    private final AccountQueryService accounts = mock(AccountQueryService.class);
    private final PinnableContentPicker picker = mock(PinnableContentPicker.class);
    private final AdminTopicStore topics = mock(AdminTopicStore.class);
    private final AdminContentPinService service =
            new AdminContentPinService(pins, posts, audit, accounts, picker, topics);

    private long nextPinId = 100;

    @BeforeEach
    void phaseIsTheRealOne() {
        when(pins.phaseOf(any(), any())).thenAnswer(inv -> {
            ContentPin p = inv.getArgument(0);
            Instant at = inv.getArgument(1);
            return ScheduleWindow.phaseAt(at, p.getStartsAt(), p.effectiveEnd());
        });
        when(topics.lockStatusForShare(anyLong())).thenReturn(Optional.of(TopicStatus.ACTIVE));
        when(pins.schedule(any())).thenAnswer(inv -> {
            ContentPin p = inv.getArgument(0);
            ReflectionTestUtils.setField(p, "id", nextPinId++);
            return p;
        });
    }

    private static TopicView topic(long id, TopicStatus status) {
        return new TopicView(id, "KucingLucu" + id, "kucinglucu" + id, TopicType.OFFICIAL, status, null, null, 3,
                BigDecimal.ONE, null, status == TopicStatus.DISABLED ? NOW : null, NOW.minus(Duration.ofDays(9)));
    }

    private ContentPin promo(String slot, Instant from, Instant to) {
        ContentPin p = ContentPin.ofPromo(slot, "https://cdn/x.jpg", "Promo " + nextPinId, null, from, to);
        ReflectionTestUtils.setField(p, "id", nextPinId++);
        return p;
    }

    private static ContentPost post(long id) {
        ContentPost p = ContentPost.publish(7, ContentType.KNOWLEDGE, null, "halo #KucingLucu", List.of());
        ReflectionTestUtils.setField(p, "id", id);
        return p;
    }

    private static Instant h(long hours) {
        return NOW.plus(Duration.ofHours(hours));
    }

    // ---------------------------------------------------------------- resolveSlot

    @Test
    void homeFeedResolvesToItself() {
        assertThat(service.resolveSlot("HOME_FEED", null)).isEqualTo(ContentPin.SLOT_HOME_FEED);
        assertThat(service.resolveSlot("HOME_FEED", 5L)).isEqualTo(ContentPin.SLOT_HOME_FEED);
    }

    @Test
    void topicPageResolvesForActiveAndHiddenTopics() {
        when(topics.findById(5L)).thenReturn(Optional.of(topic(5, TopicStatus.ACTIVE)));
        when(topics.findById(6L)).thenReturn(Optional.of(topic(6, TopicStatus.HIDDEN)));

        assertThat(service.resolveSlot("TOPIC_PAGE", 5L)).isEqualTo("TOPIC:5");
        assertThat(service.resolveSlot("TOPIC_PAGE", 6L)).isEqualTo("TOPIC:6");
    }

    @Test
    void topicPageRejectsDisabledMissingOrAbsentTopic() {
        when(topics.findById(7L)).thenReturn(Optional.of(topic(7, TopicStatus.DISABLED)));
        when(topics.findById(8L)).thenReturn(Optional.empty());

        for (Long id : new Long[] {7L, 8L, null}) {
            assertThatThrownBy(() -> service.resolveSlot("TOPIC_PAGE", id))
                    .isInstanceOf(AppException.class)
                    .extracting("messageCode").isEqualTo("admin.err.pins.topicUnavailable");
        }
    }

    @Test
    void anyOtherSlotStringIsRejected() {
        for (String raw : new String[] {"TOPIC:5", "FOO", "", " HOME_FEED", "home_feed", null}) {
            assertThatThrownBy(() -> service.resolveSlot(raw, 5L))
                    .as(String.valueOf(raw))
                    .isInstanceOf(AppException.class)
                    .extracting("messageCode").isEqualTo("admin.err.pins.slotInvalid");
        }
        verify(topics, never()).findById(anyLong());
    }

    @Test
    void topicIdOfReadsOnlyWellFormedTopicSlots() {
        assertThat(AdminContentPinService.topicIdOf("TOPIC:42")).isEqualTo(42L);
        assertThat(AdminContentPinService.topicIdOf(ContentPin.topicSlot(7))).isEqualTo(7L);
        assertThat(AdminContentPinService.topicIdOf("HOME_FEED")).isNull();
        assertThat(AdminContentPinService.topicIdOf("TOPIC:")).isNull();
        assertThat(AdminContentPinService.topicIdOf("TOPIC:-1")).isNull();
        assertThat(AdminContentPinService.topicIdOf("TOPIC:abc")).isNull();
        assertThat(AdminContentPinService.topicIdOf(null)).isNull();
    }

    // ---------------------------------------------------------------- createContentPin（话题坑位校验）

    @Test
    void topicContentPinRequiresThePostToBeInTheTopic() {
        when(posts.findById(11L)).thenReturn(Optional.of(post(11)));
        when(posts.isInTopic(11L, 5L)).thenReturn(false);

        assertThatThrownBy(() -> service.createContentPin(ADMIN, "TOPIC:5", 11L, h(1), h(5)))
                .isInstanceOf(AppException.class)
                .extracting("messageCode").isEqualTo("admin.err.pins.notInTopic");
        verify(pins, never()).schedule(any());
        verify(audit, never()).record(any(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void topicContentPinRejectsPrivateUnpublishedOrDeletedPosts() {
        ContentPost priv = post(21);
        priv.setVisibility(ContentVisibility.PRIVATE);
        ContentPost pending = post(22);
        ReflectionTestUtils.setField(pending, "status", com.tailtopia.content.domain.PostStatus.UNDER_REVIEW);
        ContentPost deleted = post(23);
        deleted.softDelete();
        for (ContentPost p : List.of(priv, pending, deleted)) {
            when(posts.findById(p.getId())).thenReturn(Optional.of(p));
            when(posts.isInTopic(p.getId(), 5L)).thenReturn(true);
            assertThatThrownBy(() -> service.createContentPin(ADMIN, "TOPIC:5", p.getId(), h(1), h(5)))
                    .as("post " + p.getId())
                    .isInstanceOf(AppException.class)
                    .extracting("messageCode").isEqualTo("admin.err.pins.notInTopic");
        }
        when(posts.findById(24L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.createContentPin(ADMIN, "TOPIC:5", 24L, h(1), h(5)))
                .extracting("messageCode").isEqualTo("admin.err.pins.notInTopic");
        verify(pins, never()).schedule(any());
    }

    @Test
    void compliantTopicContentPinIsScheduledAndAuditedOnce() {
        when(posts.findById(31L)).thenReturn(Optional.of(post(31)));
        when(posts.isInTopic(31L, 5L)).thenReturn(true);

        long id = service.createContentPin(ADMIN, "TOPIC:5", 31L, h(1), h(5));

        verify(pins).schedule(any());
        verify(audit, times(1)).record(eq(ADMIN), eq("CONTENT_PIN_CREATE"), eq("content_pin"),
                eq(String.valueOf(id)), contains("slot=TOPIC:5"));
    }

    @Test
    void feedContentPinKeepsItsOldBehaviourAndPromoSkipsTopicChecks() {
        service.createContentPin(ADMIN, "HOME_FEED", 41L, h(1), h(5));
        service.createPromoPin(ADMIN, "TOPIC:5", "https://cdn/p.jpg", "Lomba", null, h(1), h(5));

        verify(posts, never()).findById(anyLong());
        verify(posts, never()).isInTopic(anyLong(), anyLong());
        verify(pins, times(2)).schedule(any());
        verify(audit).record(eq(ADMIN), eq("CONTENT_PIN_CREATE"), eq("content_pin"), anyString(),
                contains("slot=TOPIC:5 promo=Lomba"));
    }

    // ---------------------------------------------------------------- 复审 a：与禁用话题的竞态

    @Test
    void topicPinWriteLocksTheTopicRowAndRefusesIfItWasDisabledMeanwhile() {
        when(topics.lockStatusForShare(5L)).thenReturn(Optional.of(TopicStatus.DISABLED));
        when(topics.lockStatusForShare(6L)).thenReturn(Optional.empty());
        when(posts.findById(51L)).thenReturn(Optional.of(post(51)));
        when(posts.isInTopic(anyLong(), anyLong())).thenReturn(true);

        for (String slot : List.of("TOPIC:5", "TOPIC:6")) {
            assertThatThrownBy(() -> service.createContentPin(ADMIN, slot, 51L, h(1), h(5)))
                    .extracting("messageCode").isEqualTo("admin.err.pins.topicUnavailable");
            assertThatThrownBy(() -> service.createPromoPin(ADMIN, slot, "https://cdn/p.jpg", "t", null, h(1), h(5)))
                    .extracting("messageCode").isEqualTo("admin.err.pins.topicUnavailable");
        }
        verify(pins, never()).schedule(any());
        verify(audit, never()).record(any(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void hiddenTopicStillAcceptsPinsAndTheLockIsTakenBeforeScheduling() {
        when(topics.lockStatusForShare(5L)).thenReturn(Optional.of(TopicStatus.HIDDEN));

        service.createPromoPin(ADMIN, "TOPIC:5", "https://cdn/p.jpg", "t", null, h(1), h(5));

        var order = org.mockito.Mockito.inOrder(topics, pins);
        order.verify(topics).lockStatusForShare(5L);
        order.verify(pins).schedule(any());
    }

    @Test
    void feedPinsNeverLockAnyTopic() {
        service.createPromoPin(ADMIN, "HOME_FEED", "https://cdn/p.jpg", "t", null, h(1), h(5));
        service.createContentPin(ADMIN, "HOME_FEED", 61L, h(6), h(9));

        verify(topics, never()).lockStatusForShare(anyLong());
    }

    // ---------------------------------------------------------------- topicRows（G1a）

    @Test
    void topicRowsPickOneActiveAndCountOnlyLivePendingPins() {
        TopicView a = topic(5, TopicStatus.ACTIVE);
        TopicView b = topic(6, TopicStatus.HIDDEN);
        when(topics.pinnablePage("kucing", 0)).thenReturn(new AdminTopicStore.TopicPage(List.of(a, b), 0, true));

        List<ContentPin> all = new ArrayList<>();
        ContentPin live = promo("TOPIC:5", h(-2), h(2));
        all.add(live);
        all.add(promo("TOPIC:5", h(3), h(4)));      // 排期中
        all.add(promo("TOPIC:5", h(5), h(6)));      // 排期中
        ContentPin cancelled = promo("TOPIC:5", h(7), h(8)); // 待生效但已被提前结束 → 不计
        ReflectionTestUtils.setField(cancelled, "terminatedAt", h(-1));
        all.add(cancelled);
        all.add(promo("TOPIC:5", h(-10), h(-5)));   // 已结束
        ContentPin endedEarly = promo("TOPIC:6", h(-2), h(9)); // 生效窗口内但已提前结束 → 不算生效
        ReflectionTestUtils.setField(endedEarly, "terminatedAt", h(-1));
        all.add(endedEarly);
        when(pins.listBySlots(anyCollection())).thenReturn(all);

        AdminContentPinService.TopicRowsPage page = service.topicRows("kucing", 0, NOW);

        assertThat(page.hasNext()).isTrue();
        assertThat(page.rows()).extracting(r -> r.topic().id()).containsExactly(5L, 6L);
        TopicPinRow r5 = page.rows().get(0);
        assertThat(r5.active()).isNotNull();
        assertThat(r5.active().id()).isEqualTo(live.getId());
        assertThat(r5.pendingCount()).isEqualTo(2);
        TopicPinRow r6 = page.rows().get(1);
        assertThat(r6.active()).isNull();
        assertThat(r6.pendingCount()).isZero();
        verify(pins, times(1)).listBySlots(List.of("TOPIC:5", "TOPIC:6")); // 整页一次，不逐话题查
    }

    @Test
    void topicRowsOnEmptyPageDoNotQueryPinsPerTopic() {
        when(topics.pinnablePage(null, 0)).thenReturn(new AdminTopicStore.TopicPage(List.of(), 0, false));
        when(pins.listBySlots(anyCollection())).thenReturn(List.of());

        assertThat(service.topicRows(null, 0, NOW).rows()).isEmpty();
        verify(pins, never()).listBySlot(anyString());
    }

    // ---------------------------------------------------------------- topicPinPreview（话题抽屉区块）

    @Test
    void previewShowsOneActiveAndAtMostThreePendingInStartOrder() {
        List<ContentPin> all = new ArrayList<>();
        ContentPin live = promo("TOPIC:5", h(-1), h(1));
        all.add(promo("TOPIC:5", h(40), h(41)));
        all.add(promo("TOPIC:5", h(10), h(11)));
        all.add(live);
        all.add(promo("TOPIC:5", h(30), h(31)));
        all.add(promo("TOPIC:5", h(20), h(21)));
        all.add(promo("TOPIC:5", h(50), h(51)));
        ContentPin cancelled = promo("TOPIC:5", h(2), h(3));
        ReflectionTestUtils.setField(cancelled, "terminatedAt", h(-1));
        all.add(cancelled);
        when(pins.listBySlot("TOPIC:5")).thenReturn(all);

        TopicPinPreview pv = service.topicPinPreview(5, NOW);

        assertThat(pv.active()).extracting(PinRow::id).isEqualTo(live.getId());
        assertThat(pv.pending()).extracting(PinRow::startsAt).containsExactly(h(10), h(20), h(30));
    }

    @Test
    void previewOfTopicWithoutPinsIsEmpty() {
        when(pins.listBySlot("TOPIC:9")).thenReturn(List.of());

        TopicPinPreview pv = service.topicPinPreview(9, NOW);

        assertThat(pv.active()).isNull();
        assertThat(pv.pending()).isEmpty();
    }

    // ---------------------------------------------------------------- 可配置话题

    @Test
    void requirePinnableTopicIs404ForDisabledOrMissing() {
        when(topics.findById(5L)).thenReturn(Optional.of(topic(5, TopicStatus.HIDDEN)));
        when(topics.findById(7L)).thenReturn(Optional.of(topic(7, TopicStatus.DISABLED)));
        when(topics.findById(8L)).thenReturn(Optional.empty());

        assertThat(service.requirePinnableTopic(5L).id()).isEqualTo(5L);
        for (long id : new long[] {7L, 8L}) {
            assertThatThrownBy(() -> service.requirePinnableTopic(id))
                    .isInstanceOf(AppException.class)
                    .extracting("messageCode").isEqualTo("admin.err.topics.notFound");
        }
    }
}
