package com.tailtopia.admin.moderation;

import static org.assertj.core.api.Assertions.assertThat;

import com.tailtopia.admin.audit.service.AdminAuditService;
import com.tailtopia.admin.audit.service.AuditActions;
import com.tailtopia.admin.moderation.service.AdminContentManageService;
import com.tailtopia.content.domain.ContentPost;
import com.tailtopia.content.domain.ContentType;
import com.tailtopia.content.dto.AdminContentRow;
import com.tailtopia.content.repository.ContentPostRepository;
import com.tailtopia.content.service.ContentService;
import com.tailtopia.support.ApiIntegrationTest;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;

/**
 * L1：全量内容管理（Story 4.2，需 Docker postgres+redis）。跨作者浏览/类型筛选/正文搜索；
 * 主动下架→软删 + 审计；恢复→清 deletedAt + 审计。经 {@link AdminContentManageService}（不直读 content repo）。
 */
class AdminContentManageIntegrationTest extends ApiIntegrationTest {

    @Autowired
    private AdminContentManageService contentManage;
    @Autowired
    private ContentPostRepository posts;
    @Autowired
    private ContentService contentService;
    @Autowired
    private AdminAuditService auditService;

    private long newPost(ContentType type, String text) {
        long author = newUser().getId(); // content_posts.author_id 有 FK→users
        return posts.save(ContentPost.publish(author, type, null, text, List.of())).getId();
    }

    @Test
    void browseSearchesBodyCaseInsensitiveAcrossAuthors() {
        String token = "ZxqMarker" + SEQ.incrementAndGet();
        long p1 = newPost(ContentType.DAILY, "前缀 " + token + " 后缀");
        newPost(ContentType.KNOWLEDGE, "无关内容"); // 另一作者，不应命中

        // 关键词小写也命中（ILIKE），跨作者
        List<AdminContentRow> hits = contentManage.browse(null, null, null, null, null,
                token.toLowerCase(), 0);

        assertThat(hits).extracting(AdminContentRow::id).contains(p1);
        assertThat(hits).allMatch(r -> r.textPreview().toLowerCase().contains(token.toLowerCase()));
    }

    @Test
    void browseSearchTreatsLikeMetacharsLiterally() {
        String token = "ZxqPct" + SEQ.incrementAndGet();
        long literal = newPost(ContentType.DAILY, token + "50% off");   // 含字面 %
        long wildcardTrap = newPost(ContentType.DAILY, token + "5000"); // 若 % 当通配符会被误命中

        // 搜 "<token>50%"：转义后 % 为字面 → 仅命中 literal，不命中 token+"5000"。
        List<AdminContentRow> hits = contentManage.browse(null, null, null, null, null,
                token + "50%", 0);

        assertThat(hits).extracting(AdminContentRow::id).contains(literal).doesNotContain(wildcardTrap);
    }

    @Test
    void takedownSoftDeletesAndAudits() {
        long actor = 421000L + SEQ.incrementAndGet();
        long postId = newPost(ContentType.DAILY, "待下架内容");

        contentManage.takedown(postId, "违反社区规范", actor);

        assertThat(contentService.findSummary(postId).orElseThrow().deleted()).isTrue();
        assertThat(auditService.search(null, null, actor, AuditActions.CONTENT_TAKEN_DOWN,
                PageRequest.of(0, 5)).getContent()).isNotEmpty();
    }

    @Test
    void restoreClearsDeletedAtAndAudits() {
        long actor = 422000L + SEQ.incrementAndGet();
        long postId = newPost(ContentType.DAILY, "先下架再恢复");
        contentManage.takedown(postId, "误判", actor);
        assertThat(contentService.findSummary(postId).orElseThrow().deleted()).isTrue();

        contentManage.restore(postId, actor);

        assertThat(contentService.findSummary(postId).orElseThrow().deleted()).isFalse();
        assertThat(auditService.search(null, null, actor, AuditActions.CONTENT_RESTORED,
                PageRequest.of(0, 5)).getContent()).isNotEmpty();
        // 恢复后可被 status=ONLINE 浏览到（重回公开口径）
        List<AdminContentRow> online = contentManage.browse(null, null, null, null, "ONLINE", null, 0);
        assertThat(online).extracting(AdminContentRow::id).contains(postId);
    }

    /**
     * CSV 导出（2026-08-28，取代被撤掉的「内容互动积分」页的导出）。
     *
     * <p>钉三件事：**筛选条件真的生效**、**点赞数真的在里面**、**导出记审计**。
     * 前两件决定这份表能不能用于经营汇报；第三件是"这份数据是谁什么时候导的"的唯一答案。
     */
    @Test
    void exportCsvHonoursTheFilterCarriesLikesAndWritesAudit() {
        long keep = newPost(ContentType.DAILY, "kucing oren lucu banget");
        newPost(ContentType.KNOWLEDGE, "tips merawat anjing");

        long actor = 828000L + SEQ.incrementAndGet();
        String csv = contentManage.exportCsv(actor, null, null, null, null, null, "oren");

        assertThat(csv).startsWith("post_id,type,author_id,likes,created_at_wib,status,text");
        assertThat(csv).contains(String.valueOf(keep));
        assertThat(csv).doesNotContain("tips merawat anjing")
                .as("🔴 筛选条件没带进导出 ⇒ 导出的表与屏幕上看到的不是同一份");
        assertThat(auditService.search(null, null, actor, "CONTENT_LIST_EXPORT",
                PageRequest.of(0, 5)).getContent())
                .as("🔴 导出未记审计 ⇒ 事后无从回答「这份表是谁导的」")
                .isNotEmpty();
    }

    /**
     * 🔴 **正文里的逗号与引号不许把列冲散**。
     *
     * <p>一条正文里的逗号就能让整份表从那一行起全部错列，而打开表格的人**看不出来** ——
     * 他只会觉得数据很奇怪。这类错误没有报错、没有异常，只有一份读起来"怪怪的"报表。
     */
    @Test
    void exportCsvEscapesCommasAndQuotesInTheBody() {
        long id = newPost(ContentType.DAILY, "aku bilang \"halo\", lalu dia pergi");

        String csv = contentManage.exportCsv(1L, null, null, null, null, null, "lalu dia pergi");

        // ⚠️ **只看自己那一行**，不数总行数：这个库跨多次 mvn test 不重置，
        //    上一轮跑剩的同关键词内容会让「表头 + 1 行」的计数偶发变成 3
        //    —— 那是用例太脆，不是转义坏了。第一版正是这么写的，单跑绿、全量红。
        List<String> mine = csv.lines().filter(l -> l.startsWith(id + ",")).toList();
        assertThat(mine).as("🔴 正文里的逗号/引号把这一条冲成了多行").hasSize(1);
        assertThat(mine.get(0)).endsWith("\"aku bilang \"\"halo\"\", lalu dia pergi\"");
    }
}
