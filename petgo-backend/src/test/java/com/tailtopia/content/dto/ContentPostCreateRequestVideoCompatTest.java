package com.tailtopia.content.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.tailtopia.content.domain.ContentType;
import com.tailtopia.content.domain.MediaType;
import java.util.List;
import org.junit.jupiter.api.Test;

/** L0：专题线 video Story 1.3 —— 既有兼容构造器全部视为图文帖（mediaType / video 为 null）。 */
class ContentPostCreateRequestVideoCompatTest {

    @Test
    void legacyConstructorsAreImagePosts() {
        List<ContentPostCreateRequest> all = List.of(
                new ContentPostCreateRequest(ContentType.DAILY, null, "t", null),
                new ContentPostCreateRequest(ContentType.DAILY, null, "t", null, null),
                new ContentPostCreateRequest(ContentType.DAILY, null, "t", null, null, null),
                new ContentPostCreateRequest(ContentType.DAILY, null, "t", null, null, null, null),
                new ContentPostCreateRequest(ContentType.DAILY, null, "t", null, null, null, null, null),
                new ContentPostCreateRequest(ContentType.DAILY, null, "t", null, null, null, null, null, null),
                new ContentPostCreateRequest(ContentType.DAILY, null, "t", null, null, null, null, null, null, null));
        for (ContentPostCreateRequest r : all) {
            assertThat(r.mediaType()).isNull();
            assertThat(r.video()).isNull();
            assertThat(r.mediaTypeOrImage()).isEqualTo(MediaType.IMAGE);
        }
    }
}
