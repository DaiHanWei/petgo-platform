package com.tailtopia.admin.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tailtopia.content.domain.ContentType;
import com.tailtopia.content.dto.ContentPostCreateRequest;
import com.tailtopia.content.dto.ContentPostResponse;
import com.tailtopia.content.service.ContentService;
import com.tailtopia.shared.error.AppException;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * L0：种子发布经 ContentService 写入同一池、无标记列；服务端权威校验（AC2）。
 *
 * <p>⚠️ V1.1.6 Story 12.2 起 author <b>来自表单</b>（发布身份池），不再是"登录后台账号
 * 所关联的官方作者身份"。所以本类多了两个 mock：查账号、问身份池。
 * <b>池内/池外的真实语义在 {@code AdminSeedPostIntegrationTest} 里跑真库</b>，
 * 这里只验"参数怎么传下去"和三条字数/张数校验。
 */
class AdminContentServiceTest {

    private ContentService contentService;
    private com.tailtopia.auth.repository.UserRepository users;
    private com.tailtopia.admin.virtual.service.AdminPublishIdentityService identities;
    private AdminContentService service;

    @BeforeEach
    void setUp() {
        contentService = org.mockito.Mockito.mock(ContentService.class);
        users = org.mockito.Mockito.mock(com.tailtopia.auth.repository.UserRepository.class);
        identities = org.mockito.Mockito
                .mock(com.tailtopia.admin.virtual.service.AdminPublishIdentityService.class);
        // 默认：账号存在、启用、在池内、不是运营真实账号。各用例按需覆盖。
        com.tailtopia.auth.domain.User author =
                com.tailtopia.auth.domain.User.newVirtual("virtual:t", "种子号", null, 1L);
        when(users.findById(anyLong())).thenReturn(java.util.Optional.of(author));
        when(identities.isInPool(any())).thenReturn(true);
        when(identities.isRealPublishIdentity(anyLong())).thenReturn(false);
        service = new AdminContentService(contentService, users, identities);
    }

    private ContentPostResponse stub(long id, ContentType type) {
        return new ContentPostResponse(id, type, null, "hi", null, null, Instant.now());
    }

    @Test
    void publishSeedDelegatesToContentServiceWithAdminAuthor() {
        when(contentService.publish(anyLong(), any(), anyString()))
                .thenReturn(stub(11L, ContentType.DAILY));

        ContentPostResponse out = service.publishSeed(
                99L, ContentType.DAILY, null, "今天遛狗很开心", List.of("https://cdn/a.jpg"),
                List.of(new com.tailtopia.content.domain.ImageSize(1200, 900)), true);

        assertThat(out.id()).isEqualTo(11L);
        ArgumentCaptor<Long> authorCap = ArgumentCaptor.forClass(Long.class);
        ArgumentCaptor<ContentPostCreateRequest> reqCap =
                ArgumentCaptor.forClass(ContentPostCreateRequest.class);
        verify(contentService).publish(authorCap.capture(), reqCap.capture(), anyString());
        // author 为表单选定的发布账号（服务端已校验它在身份池内）；
        // 走与用户帖完全一致的 ContentPostCreateRequest。
        assertThat(authorCap.getValue()).isEqualTo(99L);
        assertThat(reqCap.getValue().type()).isEqualTo(ContentType.DAILY);
        assertThat(reqCap.getValue().text()).isEqualTo("今天遛狗很开心");
        assertThat(reqCap.getValue().imageUrls()).containsExactly("https://cdn/a.jpg");
        // 🔴 上传时量到的原始宽高要**一起带下去**：不带的话服务端会异步下载再量一遍，
        //    于是"刚发完就刷首页"的人看到的仍是占位比例（Story 3.5 记过同一笔账）。
        assertThat(reqCap.getValue().imageSizes())
                .containsExactly(new com.tailtopia.content.domain.ImageSize(1200, 900));
    }

    @Test
    void publishSeedSupportsThreeTypes() {
        when(contentService.publish(anyLong(), any(), anyString()))
                .thenReturn(stub(1L, ContentType.KNOWLEDGE));
        for (ContentType t : List.of(ContentType.DAILY, ContentType.KNOWLEDGE, ContentType.GROWTH_MOMENT)) {
            // GROWTH_MOMENT 经 ContentService 校验宠物归属（此处 mock，归属逻辑由 ContentServiceTest 覆盖）。
            Long petId = t == ContentType.GROWTH_MOMENT ? 5L : null;
            service.publishSeed(99L, t, petId, "x", null, null, true);
        }
        verify(contentService, org.mockito.Mockito.times(3)).publish(anyLong(), any(), anyString());
    }

    @Test
    void rejectsNullType() {
        assertThatThrownBy(() -> service.publishSeed(99L, null, null, "x", null, null, true))
                .isInstanceOf(AppException.class);
        verify(contentService, never()).publish(anyLong(), any(), anyString());
    }

    @Test
    void rejectsTextOver1000() {
        String tooLong = "x".repeat(1001);
        assertThatThrownBy(() -> service.publishSeed(99L, ContentType.DAILY, null, tooLong, null, null, true))
                .isInstanceOf(AppException.class);
        verify(contentService, never()).publish(anyLong(), any(), anyString());
    }

    /**
     * 🛡 池外账号不能当作者 —— 与批量发布**同一口径**（Story 12.1 AC5 ② 说的"三处"之二）。
     *
     * <p>"不信任客户端 author" 这条原则没有因为"作者改成表单来"而放弃，只是换了守法。
     */
    @Test
    void rejectsAuthorOutsideThePublishIdentityPool() {
        when(identities.isInPool(any())).thenReturn(false);
        assertThatThrownBy(() -> service.publishSeed(99L, ContentType.DAILY, null, "x", null, null, true))
                .isInstanceOf(AppException.class);
        verify(contentService, never()).publish(anyLong(), any(), anyString());
    }

    /** 🛡 池内**真实账号** + 调用方没有 seed.publish_as_real ⇒ 挡下（能管虚拟号 ≠ 能以真人身份发言）。 */
    @Test
    void rejectsRealIdentityWhenCallerLacksTheDedicatedPermission() {
        when(identities.isRealPublishIdentity(anyLong())).thenReturn(true);
        assertThatThrownBy(() -> service.publishSeed(99L, ContentType.DAILY, null, "x", null, null, false))
                .isInstanceOf(AppException.class);
        verify(contentService, never()).publish(anyLong(), any(), anyString());
    }

    @Test
    void rejectsMoreThanNineImages() {
        List<String> ten = java.util.stream.IntStream.range(0, 10)
                .mapToObj(i -> "https://cdn/" + i + ".jpg").toList();
        assertThatThrownBy(() -> service.publishSeed(99L, ContentType.DAILY, null, "x", ten, null, true))
                .isInstanceOf(AppException.class);
        verify(contentService, never()).publish(anyLong(), any(), anyString());
    }
}
