package com.tailtopia.content.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.tailtopia.auth.dto.AuthorView;
import com.tailtopia.content.domain.ContentPost;
import com.tailtopia.content.domain.ContentType;
import com.tailtopia.content.domain.MediaType;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * L0 契约：专题线 video Story 2.1 —— {@link SharedPostResponse#of} 是唯一组装点；
 * 新增 mediaType / video，<b>仍然没有任何 id</b>（AD-15 Rule 5）。App 解析见 Story 2.3（shared_post.dart）。
 */
class SharedPostResponseContractTest {

    private final JsonMapper json = JsonMapper.builder()
            .changeDefaultPropertyInclusion(incl -> incl.withValueInclusion(JsonInclude.Include.NON_NULL))
            .build();

    @SuppressWarnings("unchecked")
    private Map<String, Object> wire(Object dto) {
        return json.convertValue(dto, Map.class);
    }

    private static final AuthorView AUTHOR = new AuthorView(7L, "小明", "https://cdn/a.jpg", false, List.of());

    @Test
    void imagePostShapeUnchangedPlusMediaType() {
        ContentPost p = ContentPost.publish(7L, ContentType.DAILY, null, "正文", List.of("https://cdn/1.jpg"));
        Map<String, Object> m = wire(SharedPostResponse.of(p, AUTHOR, PostMedia.image(p)));
        assertThat(m.keySet()).isEqualTo(Set.of("authorNickname", "authorAvatarUrl", "authorDeleted", "type", "body",
                "imageUrls", "mediaType"));
        assertThat(m).containsEntry("mediaType", "IMAGE").doesNotContainKey("video");
    }

    @Test
    void videoPostCarriesVideoButNoIds() {
        ContentPost p = ContentPost.publish(7L, ContentType.KNOWLEDGE, null, null, List.of("https://cdn/c.jpg"));
        p.attachVideo("video/7/k.mp4", 48_000, 720, 1280, 10L);
        PostMedia media = new PostMedia(MediaType.VIDEO, new VideoView("https://v/video/7/k.mp4", 48_000, 720, 1280),
                p.getImageUrls());
        Map<String, Object> m = wire(SharedPostResponse.of(p, AUTHOR, media));
        assertThat(m).containsEntry("mediaType", "VIDEO");
        assertThat(m.get("video")).isEqualTo(Map.of("url", "https://v/video/7/k.mp4", "durationMs", 48_000,
                "width", 720, "height", 1280));
        assertThat(m.keySet()).doesNotContain("id", "postId", "authorId", "petId");
    }
}
