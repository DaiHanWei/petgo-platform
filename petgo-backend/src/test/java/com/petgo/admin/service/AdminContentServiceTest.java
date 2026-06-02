package com.petgo.admin.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.petgo.content.domain.ContentType;
import com.petgo.content.dto.ContentPostCreateRequest;
import com.petgo.content.dto.ContentPostResponse;
import com.petgo.content.service.ContentService;
import com.petgo.shared.error.AppException;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** L0：种子发布经 ContentService 写入同一池、author=运营、无标记列；服务端权威校验（AC2）。 */
class AdminContentServiceTest {

    private ContentService contentService;
    private AdminContentService service;

    @BeforeEach
    void setUp() {
        contentService = org.mockito.Mockito.mock(ContentService.class);
        service = new AdminContentService(contentService);
    }

    private ContentPostResponse stub(long id, ContentType type) {
        return new ContentPostResponse(id, type, null, "hi", null, null, Instant.now());
    }

    @Test
    void publishSeedDelegatesToContentServiceWithAdminAuthor() {
        when(contentService.publish(anyLong(), any(), anyString()))
                .thenReturn(stub(11L, ContentType.DAILY));

        ContentPostResponse out = service.publishSeed(
                99L, ContentType.DAILY, null, "今天遛狗很开心", List.of("https://cdn/a.jpg"));

        assertThat(out.id()).isEqualTo(11L);
        ArgumentCaptor<Long> authorCap = ArgumentCaptor.forClass(Long.class);
        ArgumentCaptor<ContentPostCreateRequest> reqCap =
                ArgumentCaptor.forClass(ContentPostCreateRequest.class);
        verify(contentService).publish(authorCap.capture(), reqCap.capture(), anyString());
        // author 为运营账号（不信任表单）；走与用户帖完全一致的 ContentPostCreateRequest。
        assertThat(authorCap.getValue()).isEqualTo(99L);
        assertThat(reqCap.getValue().type()).isEqualTo(ContentType.DAILY);
        assertThat(reqCap.getValue().text()).isEqualTo("今天遛狗很开心");
        assertThat(reqCap.getValue().imageUrls()).containsExactly("https://cdn/a.jpg");
    }

    @Test
    void publishSeedSupportsThreeTypes() {
        when(contentService.publish(anyLong(), any(), anyString()))
                .thenReturn(stub(1L, ContentType.KNOWLEDGE));
        for (ContentType t : List.of(ContentType.DAILY, ContentType.KNOWLEDGE, ContentType.GROWTH_MOMENT)) {
            // GROWTH_MOMENT 经 ContentService 校验宠物归属（此处 mock，归属逻辑由 ContentServiceTest 覆盖）。
            Long petId = t == ContentType.GROWTH_MOMENT ? 5L : null;
            service.publishSeed(99L, t, petId, "x", null);
        }
        verify(contentService, org.mockito.Mockito.times(3)).publish(anyLong(), any(), anyString());
    }

    @Test
    void rejectsNullType() {
        assertThatThrownBy(() -> service.publishSeed(99L, null, null, "x", null))
                .isInstanceOf(AppException.class);
        verify(contentService, never()).publish(anyLong(), any(), anyString());
    }

    @Test
    void rejectsTextOver1000() {
        String tooLong = "x".repeat(1001);
        assertThatThrownBy(() -> service.publishSeed(99L, ContentType.DAILY, null, tooLong, null))
                .isInstanceOf(AppException.class);
        verify(contentService, never()).publish(anyLong(), any(), anyString());
    }

    @Test
    void rejectsMoreThanNineImages() {
        List<String> ten = java.util.stream.IntStream.range(0, 10)
                .mapToObj(i -> "https://cdn/" + i + ".jpg").toList();
        assertThatThrownBy(() -> service.publishSeed(99L, ContentType.DAILY, null, "x", ten))
                .isInstanceOf(AppException.class);
        verify(contentService, never()).publish(anyLong(), any(), anyString());
    }
}
