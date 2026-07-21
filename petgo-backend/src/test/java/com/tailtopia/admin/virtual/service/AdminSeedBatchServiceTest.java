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

/** L0（Story 9.8 Part 2）：批量逐条发 + 内容 hash 去重 + 虚拟账号校验。纯 Mockito。 */
class AdminSeedBatchServiceTest {

    private UserRepository users;
    private ContentService content;
    private SeedContentHashRepository hashes;
    private AdminAuditService audit;
    private AdminSeedBatchService svc;

    @BeforeEach
    void setUp() {
        users = Mockito.mock(UserRepository.class);
        content = Mockito.mock(ContentService.class);
        hashes = Mockito.mock(SeedContentHashRepository.class);
        audit = Mockito.mock(AdminAuditService.class);
        svc = new AdminSeedBatchService(users, content, hashes, audit);
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
        when(hashes.existsById(anyString())).thenReturn(false);

        BatchResult r = svc.publishBatch(50L, "第一条\n第二条 ||| https://x/a.jpg, https://x/b.jpg\n\n", 7L);

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
        when(hashes.existsById(anyString())).thenReturn(true, false);

        BatchResult r = svc.publishBatch(50L, "重复内容\n新内容", 7L);

        assertThat(r.published()).isEqualTo(1);
        assertThat(r.skipped()).isEqualTo(1);
        verify(content, times(1)).publish(eq(50L), any(), anyString());
    }

    @Test
    void rejectsNonVirtualAuthor() {
        User real = User.newGoogleUser("g", "e", "n", null);
        setId(real, 9L);
        when(users.findById(9L)).thenReturn(Optional.of(real));
        assertThatThrownBy(() -> svc.publishBatch(9L, "x", 7L)).isInstanceOf(AppException.class);
        verify(content, never()).publish(Mockito.anyLong(), any(), anyString());
    }

    @Test
    void rejectsDisabledVirtualAccount() {
        User v = virtual(50L);
        v.setEnabled(false);
        assertThatThrownBy(() -> svc.publishBatch(50L, "x", 7L)).isInstanceOf(AppException.class);
    }

    @Test
    void rejectsEmptyBatch() {
        virtual(50L);
        assertThatThrownBy(() -> svc.publishBatch(50L, "   ", 7L)).isInstanceOf(AppException.class);
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
