package com.tailtopia.content.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.tailtopia.content.domain.ContentPin;
import com.tailtopia.content.dto.PinnedSlotResponse;
import com.tailtopia.content.service.FeedService;
import com.tailtopia.topic.domain.Topic;
import com.tailtopia.topic.service.TopicQueryService;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** L0 · V1.4.2 social-topic Story 4.2 · AC6.2 / AC6.3：{@code pinned?topic=} 与 {@code slot=TOPIC:} 拦截（直接 new controller）。 */
class ContentFeedControllerPinnedTopicTest {

    private FeedService feed;
    private TopicQueryService topicQuery;
    private ContentFeedController controller;

    @BeforeEach
    void setUp() {
        feed = mock(FeedService.class);
        topicQuery = mock(TopicQueryService.class);
        controller = new ContentFeedController(feed, topicQuery);
    }

    @Test
    void topicSlotViaSlotParamIsEmptyAndNoServiceCall() {
        PinnedSlotResponse r = controller.pinnedSlot(null, "TOPIC:1", null);
        assertThat(r.pin()).isNull();
        verifyNoInteractions(feed);
        verifyNoInteractions(topicQuery);
    }

    @Test
    void inaccessibleTopicIsEmptyNot404() {
        when(topicQuery.findVisible("xx")).thenReturn(Optional.empty());
        PinnedSlotResponse r = controller.pinnedSlot(null, ContentPin.SLOT_HOME_FEED, "xx");
        assertThat(r.pin()).isNull();
        verifyNoInteractions(feed);
    }

    @Test
    void accessibleTopicUsesTopicSlot() {
        Topic t = mock(Topic.class);
        when(t.getId()).thenReturn(42L);
        when(topicQuery.findVisible("Anabul")).thenReturn(Optional.of(t));
        PinnedSlotResponse expected = new PinnedSlotResponse(null);
        when(feed.loadPinnedSlot(eq("TOPIC:42"), isNull())).thenReturn(expected);

        assertThat(controller.pinnedSlot(null, ContentPin.SLOT_HOME_FEED, "Anabul")).isSameAs(expected);
    }

    private static org.springframework.security.oauth2.jwt.Jwt jwt(String sub, String role) {
        return org.springframework.security.oauth2.jwt.Jwt.withTokenValue("t").header("alg", "none")
                .subject(sub).claim("role", role).build();
    }

    /** 复审 CONFIRMED-2 · AC0.4：话题坑位只认 role=USER，兽医 token 按游客处理。 */
    @Test
    void vetTokenWithTopicIsTreatedAsGuest() {
        Topic t = mock(Topic.class);
        when(t.getId()).thenReturn(42L);
        when(topicQuery.findVisible("Anabul")).thenReturn(Optional.of(t));
        when(feed.loadPinnedSlot(anyString(), any())).thenReturn(new PinnedSlotResponse(null));

        controller.pinnedSlot(jwt("7", "VET"), ContentPin.SLOT_HOME_FEED, "Anabul");
        verify(feed).loadPinnedSlot("TOPIC:42", null);

        controller.pinnedSlot(jwt("8", "USER"), ContentPin.SLOT_HOME_FEED, "Anabul");
        verify(feed).loadPinnedSlot("TOPIC:42", 8L);
    }

    /** 不带 topic 的旧路径保持原样（不校验 role，行为与改动前逐字节一致）。 */
    @Test
    void legacyPathKeepsOldViewerResolution() {
        when(feed.loadPinnedSlot(anyString(), any())).thenReturn(new PinnedSlotResponse(null));
        controller.pinnedSlot(jwt("7", "VET"), ContentPin.SLOT_HOME_FEED, null);
        verify(feed).loadPinnedSlot(ContentPin.SLOT_HOME_FEED, 7L);
    }

    @Test
    void withoutTopicPassesSlotThrough() {
        PinnedSlotResponse expected = new PinnedSlotResponse(null);
        when(feed.loadPinnedSlot(anyString(), any())).thenReturn(expected);

        assertThat(controller.pinnedSlot(null, ContentPin.SLOT_HOME_FEED, null)).isSameAs(expected);
        verify(feed).loadPinnedSlot(ContentPin.SLOT_HOME_FEED, null);
        verifyNoInteractions(topicQuery);
    }
}
