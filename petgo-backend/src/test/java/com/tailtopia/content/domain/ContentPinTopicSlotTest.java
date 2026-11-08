package com.tailtopia.content.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** L0 · V1.4.2 social-topic Story 4.2 · AC6.1：话题坑位值的唯一拼法。 */
class ContentPinTopicSlotTest {

    @Test
    void topicSlotFormat() {
        assertThat(ContentPin.topicSlot(42)).isEqualTo("TOPIC:42");
        assertThat(ContentPin.topicSlot(42)).startsWith(ContentPin.SLOT_TOPIC_PREFIX);
    }

    @Test
    void longestSlotFitsColumn() {
        // content_pins.slot VARCHAR(32)
        assertThat(ContentPin.topicSlot(Long.MAX_VALUE).length()).isLessThanOrEqualTo(32);
    }

    @Test
    void homeFeedSlotUnchanged() {
        assertThat(ContentPin.SLOT_HOME_FEED).isEqualTo("HOME_FEED");
        assertThat(ContentPin.SLOT_HOME_FEED).doesNotStartWith(ContentPin.SLOT_TOPIC_PREFIX);
    }
}
