package com.tailtopia.admin.virtual.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tailtopia.admin.audit.service.AdminAuditService;
import com.tailtopia.admin.virtual.repository.SeedContentHashRepository;
import com.tailtopia.admin.virtual.service.AdminSeedBatchService.BatchResult;
import com.tailtopia.auth.domain.User;
import com.tailtopia.auth.repository.UserRepository;
import com.tailtopia.content.dto.ContentPostCreateRequest;
import com.tailtopia.content.dto.ContentPostResponse;
import com.tailtopia.content.service.ContentService;
import com.tailtopia.shared.error.AppException;
import java.util.Optional;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.mock.web.MockMultipartFile;

/**
 * L0（Story 9.8 Part 2）：批量逐条发 + 内容 hash 去重 + 发布身份校验。纯 Mockito。
 *
 * <p>⚠️ V1.1.6 Story 12.1 起，「谁能当作者」的判定不再是这里的一句
 * {@code accountType != VIRTUAL}，而是问身份池（{@link AdminPublishIdentityService#isInPool}）——
 * 所以本类给它一个 mock。<b>池内/池外的真实语义在
 * {@code AdminPublishIdentityIntegrationTest} 里跑真库</b>，这里只验批量逻辑本身。
 */
class AdminSeedBatchServiceTest {

    private UserRepository users;
    private ContentService content;
    private SeedContentHashRepository hashes;
    private AdminAuditService audit;
    private AdminPublishIdentityService identities;
    private AdminSeedBatchService svc;

    @BeforeEach
    void setUp() {
        users = Mockito.mock(UserRepository.class);
        content = Mockito.mock(ContentService.class);
        hashes = Mockito.mock(SeedContentHashRepository.class);
        audit = Mockito.mock(AdminAuditService.class);
        identities = Mockito.mock(AdminPublishIdentityService.class);
        // 默认：虚拟账号在池内、不是"运营真实账号"。各用例按需覆盖。
        when(identities.isInPool(any())).thenAnswer(inv -> {
            User u = inv.getArgument(0);
            return u.getAccountType() == com.tailtopia.auth.domain.AccountType.VIRTUAL;
        });
        when(identities.isRealPublishIdentity(Mockito.anyLong())).thenReturn(false);
        svc = new AdminSeedBatchService(users, content, hashes, identities, audit);
        when(content.publish(Mockito.anyLong(), any(), anyString()))
                .thenReturn(Mockito.mock(ContentPostResponse.class));
    }

    private User virtual(long id) {
        User u = User.newVirtual("virtual:" + id, "喵", null, 1L);
        setId(u, id);
        when(users.findById(id)).thenReturn(Optional.of(u));
        return u;
    }

    @Test
    void publishesEachLineUnderVirtualAccount() {
        User v = virtual(50L);
        // 🔴 V1.1.6 Story 13.4：去重判据加了**作者维度** —— 原先是 existsById(hash) 单列。
        when(hashes.existsByContentHashAndAuthorId(anyString(), Mockito.anyLong()))
                .thenReturn(false);

        BatchResult r = svc.publishBatch(50L, "第一条\n第二条 ||| https://x/a.jpg, https://x/b.jpg\n\n", 7L, true);

        assertThat(r.published()).isEqualTo(2);
        assertThat(r.skipped()).isEqualTo(0);
        assertThat(v.getPublishedCount()).isEqualTo(2);
        verify(content, times(2)).publish(eq(50L), any(), anyString());
        verify(hashes, times(2)).save(any());
        verify(audit).record(eq(7L), eq("SEED_BATCH_PUBLISH"), anyString(), eq("50"), anyString());

        // 第二条带 2 图。
        ArgumentCaptor<ContentPostCreateRequest> cap = ArgumentCaptor.forClass(ContentPostCreateRequest.class);
        verify(content, times(2)).publish(eq(50L), cap.capture(), anyString());
        assertThat(cap.getAllValues().get(1).imageUrls()).containsExactly("https://x/a.jpg", "https://x/b.jpg");
    }

    @Test
    void skipsDuplicateContentByHash() {
        virtual(50L);
        // 首条 hash 已存在 → 跳过；次条新 → 发。
        when(hashes.existsByContentHashAndAuthorId(anyString(), Mockito.anyLong()))
                .thenReturn(true, false);

        BatchResult r = svc.publishBatch(50L, "重复内容\n新内容", 7L, true);

        assertThat(r.published()).isEqualTo(1);
        assertThat(r.skipped()).isEqualTo(1);
        verify(content, times(1)).publish(eq(50L), any(), anyString());
    }

    /** 池外账号（这里是个普通真实用户）不能当作者 —— 判据已从"账号类型"换成"在不在池内"。 */
    @Test
    void rejectsAuthorOutsideThePool() {
        User real = User.newGoogleUser("g", "e", "n", null);
        setId(real, 9L);
        when(users.findById(9L)).thenReturn(Optional.of(real));
        assertThatThrownBy(() -> svc.publishBatch(9L, "x", 7L, true)).isInstanceOf(AppException.class);
        verify(content, never()).publish(Mockito.anyLong(), any(), anyString());
    }

    @Test
    void rejectsDisabledVirtualAccount() {
        User v = virtual(50L);
        v.setEnabled(false);
        assertThatThrownBy(() -> svc.publishBatch(50L, "x", 7L, true)).isInstanceOf(AppException.class);
    }

    /**
     * 🛡 池内真实账号 + 调用方**没有** {@code seed.publish_as_real} ⇒ 挡下。
     *
     * <p>这个布尔是**显式入参**而不是服务里偷读 SecurityContext ——
     * 三处发布入口都要做这个检查，而"忘记检查"是静默的；做成参数就让漏掉变成编译错误。
     */
    @Test
    void rejectsRealIdentityWhenCallerLacksTheDedicatedPermission() {
        User real = User.newGoogleUser("g2", "e2", "n2", null);
        setId(real, 77L);
        when(users.findById(77L)).thenReturn(Optional.of(real));
        when(identities.isInPool(real)).thenReturn(true);
        when(identities.isRealPublishIdentity(77L)).thenReturn(true);

        assertThatThrownBy(() -> svc.publishBatch(77L, "x", 7L, false))
                .isInstanceOf(AppException.class);
        verify(content, never()).publish(Mockito.anyLong(), any(), anyString());
    }

    @Test
    void rejectsEmptyBatch() {
        virtual(50L);
        assertThatThrownBy(() -> svc.publishBatch(50L, "   ", 7L, true)).isInstanceOf(AppException.class);
    }

    @Test
    void readsExcelRowsAsBatchLines() throws Exception {
        try (var wb = new XSSFWorkbook()) {
            var sheet = wb.createSheet();
            var header = sheet.createRow(0);
            header.createCell(0).setCellValue("文本");
            header.createCell(1).setCellValue("图片");
            var row = sheet.createRow(1);
            row.createCell(0).setCellValue("第一条");
            row.createCell(1).setCellValue("https://x/a.jpg, https://x/b.jpg");
            var out = new java.io.ByteArrayOutputStream();
            wb.write(out);

            String lines = svc.readLines(new MockMultipartFile("file", "seed.xlsx",
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", out.toByteArray()));

            assertThat(lines).isEqualTo("第一条 ||| https://x/a.jpg, https://x/b.jpg\n");
        }
    }

    private static void setId(User u, long id) {
        try {
            var f = User.class.getDeclaredField("id");
            f.setAccessible(true);
            f.set(u, id);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
