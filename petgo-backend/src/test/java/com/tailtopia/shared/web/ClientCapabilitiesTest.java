package com.tailtopia.shared.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** L0：专题线 video Story 2.2 —— 能力声明解析（大小写不敏感、去空白、未知值忽略、缺失即旧版）。 */
class ClientCapabilitiesTest {

    @Test
    void parsesVideoCapability() {
        assertThat(ClientCapabilities.includesVideo("video")).isTrue();
        assertThat(ClientCapabilities.includesVideo(" Video ")).isTrue();
        assertThat(ClientCapabilities.includesVideo("foo, video,bar")).isTrue();
    }

    @Test
    void missingBlankOrUnknownMeansOldClient() {
        assertThat(ClientCapabilities.includesVideo(null)).isFalse();
        assertThat(ClientCapabilities.includesVideo("")).isFalse();
        assertThat(ClientCapabilities.includesVideo("  ")).isFalse();
        assertThat(ClientCapabilities.includesVideo("videos,live")).isFalse();
    }
}
