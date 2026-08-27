package com.tailtopia.content.larksync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.tailtopia.auth.domain.User;
import com.tailtopia.auth.repository.UserRepository;
import com.tailtopia.content.domain.ContentType;
import com.tailtopia.content.dto.ContentPostCreateRequest;
import com.tailtopia.content.dto.ContentPostResponse;
import com.tailtopia.content.service.ContentService;
import com.tailtopia.shared.media.AliyunOssClient;
import com.tailtopia.shared.media.MediaProperties;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

/** 编排逻辑（spec-lark-scheduled-posts I/O 矩阵）：mock 全部出站依赖，纯 L0。 */
class LarkContentSyncServiceTest {

    private LarkContentSyncProperties props;
    private LarkContentClient client;
    private LarkContentPublishRepository records;
    private ContentService contentService;
    private AliyunOssClient oss;
    private MediaProperties mediaProps;
    private UserRepository users;
    private PlatformTransactionManager txManager;
    private LarkContentSyncService service;

    @BeforeEach
    void setUp() {
        props = new LarkContentSyncProperties();
        props.setMode("live");
        props.setAuthorIds(List.of(7L));
        client = mock(LarkContentClient.class);
        records = mock(LarkContentPublishRepository.class);
        contentService = mock(ContentService.class);
        oss = mock(AliyunOssClient.class);
        mediaProps = new MediaProperties();
        users = mock(UserRepository.class);
        txManager = mock(PlatformTransactionManager.class);
        when(txManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        service = new LarkContentSyncService(props, client, records, contentService,
                oss, mediaProps, users, txManager);

        when(records.findByContentCode(anyString())).thenReturn(Optional.empty());
        when(records.save(any())).thenAnswer(inv -> inv.getArgument(0));
        User author = User.newVirtual("syn-7", "Si Oyen", null, 1L);
        when(users.findById(7L)).thenReturn(Optional.of(author));
        when(oss.putPublicObjectWithAcl(anyString(), any(), anyString()))
                .thenAnswer(inv -> "https://cdn.example/" + inv.getArgument(0));
        when(contentService.publishTrusted(Mockito.anyLong(), any(), anyString()))
                .thenReturn(response(101L));
        // 表头动态列映射（2026-08-27 实测）：C=编号 D=文案 E=图片前缀 F=邮箱 G=状态 H=账号 I=备注。
        when(client.readHeader()).thenReturn(List.of(
                "序号", "内容分类", "内容编号", "文案部分(最多1000字)", "图片编号",
                "发布账号(邮箱)，不填默认虚拟账号随机", "上传状态", "发布账号", "备注(代码填写，人不填)"));
        // 回写重定位：默认 DR001→行2 / DR002→行3（与各用例的 readRows 顺序一致）。
        when(client.findRowByCode("C", "DR001")).thenReturn(Optional.of(2));
        when(client.findRowByCode("C", "DR002")).thenReturn(Optional.of(3));
    }

    private static ContentPostResponse response(long id) {
        return new ContentPostResponse(id, ContentType.DAILY, null, "t",
                List.of(), null, Instant.now());
    }

    private static List<String> row(String code, String text, String img, String status) {
        return row(code, text, img, "", status);
    }

    private static List<String> row(String code, String text, String img, String email,
            String status) {
        return Arrays.asList("1", "Moment", code, text, img, email, status, "", "");
    }

    @Test
    void mode为off_零外部调用() {
        props.setMode("off");
        service.syncOnce();
        verifyNoInteractions(client, records, contentService, oss, users);
    }

    @Test
    void mode值无效_等同off零调用() {
        props.setMode("on");
        service.syncOnce();
        verifyNoInteractions(client, records, contentService, oss, users);
    }

    @Test
    void 多行待发_每轮恰好发一条且回写EF() {
        when(client.readRows()).thenReturn(List.of(
                row("DR001", "text-1", "DR001", ""),
                row("DR002", "text-2", "DR002", "")));
        when(client.listFolderFiles()).thenReturn(Map.of(
                "DR001-1.jpg", "tok1", "DR002-1.jpg", "tok2"));
        when(client.downloadFile("tok1")).thenReturn(new byte[] {1});

        service.syncOnce();

        // 恰好一条：只发第一行，第二行留给下小时。
        verify(contentService, times(1)).publishTrusted(eq(7L), any(), eq("lark-content:DR001"));
        verify(contentService, never()).publishTrusted(Mockito.anyLong(), any(), eq("lark-content:DR002"));
        // OSS key 带内容编号目录；回写「已发布 ... WIB」+ 昵称（行号经 findRowByCode 重定位）。
        verify(oss).putPublicObjectWithAcl(startsWith("public/lark-content/DR001/"), any(), eq("image/jpeg"));
        verify(client).writeCell(eq("G"), eq(2), contains("已发布"));
        verify(client).writeCell(eq("H"), eq(2), eq("Si Oyen"));
        // DB 状态机落 PUBLISHED。
        ArgumentCaptor<LarkContentPublish> saved = ArgumentCaptor.forClass(LarkContentPublish.class);
        verify(records).save(saved.capture());
        assertEquals(LarkContentPublish.Status.PUBLISHED, saved.getValue().getStatus());
        assertEquals(Long.valueOf(101L), saved.getValue().getPostId());
    }

    @Test
    void 多图行_按前缀匹配云盘并按序号升序入imageUrls() {
        when(client.readRows()).thenReturn(List.of(
                row("DR001", "text-1", "DR001", "")));
        // 文件夹乱序 + 序号 10 > 2（按整数排非字典序）+ 非法后缀（-1.1 / -A / -1-1 / 别的前缀）不认。
        when(client.listFolderFiles()).thenReturn(Map.of(
                "DR001-10.jpg", "tok10", "DR001-2.jpg", "tok2", "DR001-1.jpg", "tok1",
                "DR001-1.1.jpg", "bad1", "DR001-A.jpg", "bad2", "DR001-1-1.jpg", "bad3",
                "DR0011-1.jpg", "other"));
        when(client.downloadFile(anyString())).thenReturn(new byte[] {1});

        service.syncOnce();

        ArgumentCaptor<ContentPostCreateRequest> req =
                ArgumentCaptor.forClass(ContentPostCreateRequest.class);
        verify(contentService).publishTrusted(eq(7L), req.capture(), eq("lark-content:DR001"));
        assertEquals(List.of(
                "https://cdn.example/public/lark-content/DR001/DR001-1.jpg",
                "https://cdn.example/public/lark-content/DR001/DR001-2.jpg",
                "https://cdn.example/public/lark-content/DR001/DR001-10.jpg"),
                req.getValue().imageUrls());
        verify(client, never()).downloadFile("bad1");
        verify(client, never()).downloadFile("other");
    }

    @Test
    void 图片编号带序号_视为无效不下载() {
        when(client.readRows()).thenReturn(List.of(
                row("DR001", "text-1", "DR001-1", ""),
                row("DR002", "text-2", "DR002", "")));
        when(client.listFolderFiles()).thenReturn(Map.of(
                "DR001-1.jpg", "tok1", "DR002-1.jpg", "tok2"));
        when(client.downloadFile("tok2")).thenReturn(new byte[] {2});

        service.syncOnce();

        verify(client).writeCell(eq("G"), eq(2), eq("无效"));
        verify(client).writeCell(eq("I"), eq(2), contains("不得带「-」"));
        verify(client, never()).downloadFile("tok1");
        verify(contentService, times(1)).publishTrusted(eq(7L), any(), eq("lark-content:DR002"));
    }

    @Test
    void 云盘匹配超9张_无效() {
        when(client.readRows()).thenReturn(List.of(row("DR001", "text-1", "DR001", "")));
        java.util.Map<String, String> folder = new java.util.HashMap<>();
        for (int i = 1; i <= 10; i++) {
            folder.put("DR001-" + i + ".jpg", "tok" + i);
        }
        when(client.listFolderFiles()).thenReturn(folder);

        service.syncOnce();

        verify(client).writeCell(eq("I"), eq(2), contains("超过 9 张"));
        verifyNoInteractions(contentService, oss);
    }

    @Test
    void 指定邮箱_匹配ACTIVE用户为作者_不碰作者池() {
        when(client.readRows()).thenReturn(List.of(
                row("DR001", "text-1", "DR001", "Ops@Example.com", "")));
        when(client.listFolderFiles()).thenReturn(Map.of("DR001-1.jpg", "tok1"));
        when(client.downloadFile("tok1")).thenReturn(new byte[] {1});
        User named = User.newVirtual("syn-42", "Ops Person", null, 1L);
        org.springframework.test.util.ReflectionTestUtils.setField(named, "id", 42L);
        when(users.findByEmailAndRole("Ops@Example.com", com.tailtopia.auth.domain.Role.USER))
                .thenReturn(Optional.of(named));
        when(users.findById(42L)).thenReturn(Optional.of(named));

        service.syncOnce();

        verify(contentService).publishTrusted(eq(42L), any(), eq("lark-content:DR001"));
        verify(users, never()).findById(7L);
        verify(client).writeCell(eq("H"), eq(2), eq("Ops Person"));
    }

    @Test
    void 指定邮箱_匹配不到_无效并备注原因顺延() {
        when(client.readRows()).thenReturn(List.of(
                row("DR001", "text-1", "DR001", "nobody@example.com", ""),
                row("DR002", "text-2", "DR002", "")));
        when(users.findByEmailAndRole("nobody@example.com", com.tailtopia.auth.domain.Role.USER))
                .thenReturn(Optional.empty());
        when(client.listFolderFiles()).thenReturn(Map.of("DR002-1.jpg", "tok2"));
        when(client.downloadFile("tok2")).thenReturn(new byte[] {2});

        service.syncOnce();

        verify(client).writeCell(eq("G"), eq(2), eq("无效"));
        verify(client).writeCell(eq("I"), eq(2), contains("未匹配到有效用户"));
        // PII 红线：备注/日志/DB 都不回显邮箱。
        verify(client, never()).writeCell(anyString(), anyInt(), contains("nobody@example.com"));
        verify(contentService, never()).publishTrusted(Mockito.anyLong(), any(), eq("lark-content:DR001"));
        verify(contentService, times(1)).publishTrusted(eq(7L), any(), eq("lark-content:DR002"));
    }

    @Test
    void 指定邮箱是真实注册的官方运营号_照常以其身份发布() {
        when(client.readRows()).thenReturn(List.of(
                row("DR001", "text-1", "DR001", "ops@tailtopia.id", "")));
        when(client.listFolderFiles()).thenReturn(Map.of("DR001-1.jpg", "tok1"));
        when(client.downloadFile("tok1")).thenReturn(new byte[] {1});
        User real = User.newGoogleUser("g-1", "ops@tailtopia.id", "TailTopia Official", null);
        org.springframework.test.util.ReflectionTestUtils.setField(real, "id", 99L);
        when(users.findByEmailAndRole("ops@tailtopia.id", com.tailtopia.auth.domain.Role.USER))
                .thenReturn(Optional.of(real));
        when(users.findById(99L)).thenReturn(Optional.of(real));

        service.syncOnce();

        verify(contentService).publishTrusted(eq(99L), any(), eq("lark-content:DR001"));
        verify(client).writeCell(eq("H"), eq(2), eq("TailTopia Official"));
    }

    @Test
    void 指定邮箱格式非法_无效() {
        when(client.readRows()).thenReturn(List.of(
                row("DR001", "text-1", "DR001", "not-an-email", "")));
        service.syncOnce();
        verify(client).writeCell(eq("I"), eq(2), contains("不是合法邮箱"));
        verifyNoInteractions(contentService, oss);
        verify(users, never()).findByEmailAndRole(anyString(), any());
    }

    @Test
    void 发布成功_备注清空() {
        when(client.readRows()).thenReturn(List.of(row("DR001", "text-1", "DR001", "")));
        when(client.listFolderFiles()).thenReturn(Map.of("DR001-1.jpg", "tok1"));
        when(client.downloadFile("tok1")).thenReturn(new byte[] {1});
        service.syncOnce();
        verify(client).writeCell(eq("I"), eq(2), eq(""));
    }

    @Test
    void DB已PUBLISHED但表格状态空_只补回写不占额度() {
        when(client.readRows()).thenReturn(List.of(
                row("DR001", "text-1", "DR001", ""),
                row("DR002", "text-2", "DR002", "")));
        when(records.findByContentCode("DR001")).thenReturn(Optional.of(
                LarkContentPublish.published("DR001", "DR001-1", 7L, 55L)));
        when(client.listFolderFiles()).thenReturn(Map.of("DR002-1.jpg", "tok2"));
        when(client.downloadFile("tok2")).thenReturn(new byte[] {2});

        service.syncOnce();

        // DR001 补回写（行2），不再发帖；额度用于 DR002（行3）。
        verify(contentService, never()).publishTrusted(Mockito.anyLong(), any(), eq("lark-content:DR001"));
        verify(client).writeCell(eq("G"), eq(2), contains("已发布"));
        verify(client).writeCell(eq("H"), eq(2), eq("Si Oyen"));
        verify(contentService, times(1)).publishTrusted(eq(7L), any(), eq("lark-content:DR002"));
        verify(client).writeCell(eq("G"), eq(3), contains("已发布"));
        verify(client).writeCell(eq("H"), eq(3), eq("Si Oyen"));
    }

    @Test
    void 首选行缺图_记FAILED回写并顺延下一行成功一条() {
        when(client.readRows()).thenReturn(List.of(
                row("DR001", "text-1", "DR001", ""),
                row("DR002", "text-2", "DR002", "")));
        // 文件夹里只有 DR002 的图 → DR001 缺图失败顺延。
        when(client.listFolderFiles()).thenReturn(Map.of("DR002-1.jpg", "tok2"));
        when(client.downloadFile("tok2")).thenReturn(new byte[] {2});

        service.syncOnce();

        verify(client).writeCell(eq("G"), eq(2), eq("无效"));
        verify(contentService, times(1)).publishTrusted(eq(7L), any(), eq("lark-content:DR002"));
        // 两次落库：DR001 FAILED + DR002 PUBLISHED。
        ArgumentCaptor<LarkContentPublish> saved = ArgumentCaptor.forClass(LarkContentPublish.class);
        verify(records, times(2)).save(saved.capture());
        assertEquals(LarkContentPublish.Status.FAILED, saved.getAllValues().get(0).getStatus());
        assertEquals(LarkContentPublish.Status.PUBLISHED, saved.getAllValues().get(1).getStatus());
    }

    @Test
    void 多图缺一_整行FAILED顺延() {
        when(client.readRows()).thenReturn(List.of(
                row("DR001", "text-1", "DR001A, DR001B", ""),
                row("DR002", "text-2", "DR002", "")));
        // DR001 两个前缀只有第一个在文件夹有图 → 整行失败。
        when(client.listFolderFiles()).thenReturn(Map.of(
                "DR001A-1.jpg", "tok1", "DR002-1.jpg", "tok2"));
        when(client.downloadFile(anyString())).thenReturn(new byte[] {1});

        service.syncOnce();

        verify(contentService, never()).publishTrusted(Mockito.anyLong(), any(), eq("lark-content:DR001"));
        verify(client).writeCell(eq("I"), eq(2), contains("缺图"));
        verify(contentService, times(1)).publishTrusted(eq(7L), any(), eq("lark-content:DR002"));
    }

    @Test
    void 文案超长_下载前拦截不碰OSS并顺延() {
        when(client.readRows()).thenReturn(List.of(
                row("DR001", "x".repeat(1001), "DR001", ""),
                row("DR002", "text-2", "DR002", "")));
        when(client.listFolderFiles()).thenReturn(Map.of(
                "DR001-1.jpg", "tok1", "DR002-1.jpg", "tok2"));
        when(client.downloadFile("tok2")).thenReturn(new byte[] {2});

        service.syncOnce();

        verify(client).writeCell(eq("G"), eq(2), eq("无效"));
        // 前置校验拦截：DR001 的图一张都不该下载/上传。
        verify(client, never()).downloadFile("tok1");
        verify(oss, never()).putPublicObjectWithAcl(startsWith("public/lark-content/DR001/"), any(), anyString());
        verify(contentService, times(1)).publishTrusted(eq(7L), any(), eq("lark-content:DR002"));
    }

    @Test
    void 状态列非空的行_直接跳过() {
        when(client.readRows()).thenReturn(List.of(
                row("DR001", "text-1", "DR001", "已发布 2026-08-24 10:07 WIB")));
        service.syncOnce();
        verifyNoInteractions(contentService, oss);
        verify(client, never()).writeCell(anyString(), anyInt(), anyString());
    }

    @Test
    void 读表失败_本轮静默退出不写脏状态() {
        when(client.readRows()).thenThrow(new LarkContentClient.LarkApiException("读取表格 失败 code=91403"));
        service.syncOnce();
        verifyNoInteractions(contentService, oss, records);
        verify(client, never()).writeCell(anyString(), anyInt(), anyString());
    }

    @Test
    void 云盘列表失败_轮级中止_绝不把行涂成失败() {
        when(client.readRows()).thenReturn(List.of(
                row("DR001", "text-1", "DR001", ""),
                row("DR002", "text-2", "DR002", "")));
        when(client.listFolderFiles())
                .thenThrow(new LarkContentClient.LarkApiException("列云盘文件夹 失败 code=1061004"));

        service.syncOnce();

        // 传输层失败：不发帖、不落 FAILED、不回写任何「失败」——下小时重试。
        verifyNoInteractions(contentService, oss);
        verify(records, never()).save(any());
        verify(client, never()).writeCell(anyString(), anyInt(), anyString());
    }

    @Test
    void 下载失败按平台异常_轮级中止不涂表() {
        when(client.readRows()).thenReturn(List.of(row("DR001", "text-1", "DR001", "")));
        when(client.listFolderFiles()).thenReturn(Map.of("DR001-1.jpg", "tok1"));
        when(client.downloadFile("tok1"))
                .thenThrow(new LarkContentClient.LarkApiException("下载内容非图片（疑似错误体）token=tok1"));

        service.syncOnce();

        verifyNoInteractions(contentService);
        verify(records, never()).save(any());
        verify(client, never()).writeCell(anyString(), anyInt(), anyString());
    }

    @Test
    void 连续行失败_熔断中止本轮() {
        List<List<String>> rows = new ArrayList<>();
        for (int i = 1; i <= 7; i++) {
            String code = "DR00" + i;
            rows.add(row(code, "text-" + i, code, ""));
            when(client.findRowByCode("C", code)).thenReturn(Optional.of(i + 1));
        }
        when(client.readRows()).thenReturn(rows);
        when(client.listFolderFiles()).thenReturn(Map.of()); // 全部缺图 → 行级失败连发。

        service.syncOnce();

        // 熔断阈值 5：只有前 5 行被标 FAILED，第 6/7 行不再触碰。
        verify(records, times(LarkContentSyncService.MAX_ROW_FAILURES)).save(any());
        verify(client, never()).findRowByCode("C", "DR006");
        verify(client, never()).findRowByCode("C", "DR007");
        verify(contentService, never()).publishTrusted(Mockito.anyLong(), any(), anyString());
    }

    @Test
    void 重复内容编号_后行标重复且绝不碰DB() {
        when(client.readRows()).thenReturn(List.of(
                row("DR001", "text-1", "DR001", "已发布 2026-08-24 10:07 WIB"),
                row("DR001", "text-别的内容", "DR001", "")));

        service.syncOnce();

        // 第二行（行3）是重复编号：只按快照行号回写提醒，不发帖不落库。
        verify(client).writeCell(eq("I"), eq(3), contains("重复"));
        verify(contentService, never()).publishTrusted(Mockito.anyLong(), any(), anyString());
        verify(records, never()).save(any());
    }

    @Test
    void 回写前重定位_行号漂移也写对行() {
        when(client.readRows()).thenReturn(List.of(row("DR001", "text-1", "DR001", "")));
        when(client.listFolderFiles()).thenReturn(Map.of("DR001-1.jpg", "tok1"));
        when(client.downloadFile("tok1")).thenReturn(new byte[] {1});
        // 快照时 DR001 在行2；回写前运营在上方插了 3 行 → 现在在行5。
        when(client.findRowByCode("C", "DR001")).thenReturn(Optional.of(5));

        service.syncOnce();

        verify(client).writeCell(eq("G"), eq(5), contains("已发布"));
        verify(client, never()).writeCell(anyString(), eq(2), anyString());
    }

    @Test
    void 纯文字行_无图也能发布() {
        when(client.readRows()).thenReturn(List.of(row("DR001", "hanya teks", "", "")));
        service.syncOnce();
        ArgumentCaptor<ContentPostCreateRequest> req =
                ArgumentCaptor.forClass(ContentPostCreateRequest.class);
        verify(contentService).publishTrusted(eq(7L), req.capture(), eq("lark-content:DR001"));
        assertEquals("hanya teks", req.getValue().text());
        assertEquals(List.of(), req.getValue().imageUrls());
        verifyNoInteractions(oss);
    }
}
