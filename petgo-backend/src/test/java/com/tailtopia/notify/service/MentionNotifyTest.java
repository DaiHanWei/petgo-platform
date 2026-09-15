package com.tailtopia.notify.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tailtopia.content.event.ContentMentionedEvent;
import com.tailtopia.notify.domain.NotificationType;
import com.tailtopia.social.read.UserHideRelationReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * L0：被 @ 的通知（V1.3.0 batch-b1 Story 3.4 · AC1/AC3/AC5）。
 *
 * <h2>🔴 第一组用例就是 AC1 —— 它是**验收条件，不是提醒**</h2>
 * {@code ck_notifications_type} 已经出过**四次**漏值事故，每次都是照着一份过期清单抄，
 * 结果那几类通知一写库就 23514、服务起不来。所以这里用机械检查钉死：
 * 迁移里的清单必须与 {@code NotificationType} 枚举<b>双向相等</b>。
 * 这条用例的价值在于：下一个人加通知类型时，忘了重列 CHECK 会**在 L0 就红**，
 * 而不是等到 staging 起不来。
 */
class MentionNotifyTest {

    private static final long ACTOR = 9L;
    private static final long AUTHOR = 7L;
    private static final Instant AT = Instant.parse("2026-09-15T16:00:00Z");

    private NotificationService notifications;
    private UserHideRelationReader hideRelations;
    private MentionNotifyListener listener;

    @BeforeEach
    void setUp() {
        notifications = mock(NotificationService.class);
        hideRelations = mock(UserHideRelationReader.class);
        listener = new MentionNotifyListener(notifications, hideRelations);
        when(hideRelations.isHidden(anyLong(), anyLong())).thenReturn(false);
        when(hideRelations.hiddenEitherWay(anyLong(), anyCollection())).thenReturn(Set.of());
    }

    private static ContentMentionedEvent event(Long commentId, List<Long> mentioned) {
        return new ContentMentionedEvent(123L, commentId, ACTOR, AUTHOR, mentioned, AT);
    }

    // ===== 🔴 AC1：CHECK 约束全集（机械检查）=====

    @Test
    void 迁移里的type清单必须与枚举双向相等() throws IOException {
        Set<String> enumValues = Arrays.stream(NotificationType.values())
                .map(Enum::name).collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        Set<String> inMigration = lastRebuiltTypeValues();

        // 🔴 双向断言，缺一边都不够：
        //   少了「枚举有而迁移无」→ 那几类通知一写库就 23514（四次事故全是这一种）；
        //   少了「迁移有而枚举无」→ 约束里挂着一个代码里已经删掉的值，下一个人照抄时会一直传下去。
        assertThat(enumValues)
                .as("枚举有而 CHECK 清单没有的值（写库即 23514，服务起不来）")
                .allSatisfy(v -> assertThat(inMigration).contains(v));
        assertThat(inMigration)
                .as("CHECK 清单里有而枚举已删的值（过期清单的来源）")
                .allSatisfy(v -> assertThat(enumValues).contains(v));
        assertThat(inMigration).hasSameSizeAs(enumValues);
    }

    @Test
    void 最后一条重建迁移必须是先DROP再ADD而不是打补丁() throws IOException {
        String sql = Files.readString(lastRebuildMigration());
        // 「在旧清单上打补丁」是四次事故的共同形状 —— 约束的改法只有 DROP + ADD 重列全集。
        assertThat(sql).contains("DROP CONSTRAINT IF EXISTS ck_notifications_type");
        assertThat(sql).contains("ADD CONSTRAINT ck_notifications_type CHECK (type IN (");
    }

    @Test
    void 新类型确实进了枚举() {
        assertThat(Arrays.stream(NotificationType.values()).map(Enum::name))
                .contains("CONTENT_MENTIONED");
    }

    // ===== AC3：一个类型、两套文案、targetRef 分流 =====

    @Test
    void 正文提及用POST文案与POST前缀() {
        listener.onContentMentioned(event(null, List.of(2L)));
        verify(notifications).sendWithCopy(eq(2L), eq(NotificationType.CONTENT_MENTIONED),
                eq("CONTENT_MENTIONED.POST"), any(), any(), any(), eq("POST:123"));
    }

    @Test
    void 评论提及用COMMENT文案与COMMENT前缀() {
        listener.onContentMentioned(event(55L, List.of(2L)));
        verify(notifications).sendWithCopy(eq(2L), eq(NotificationType.CONTENT_MENTIONED),
                eq("CONTENT_MENTIONED.COMMENT"), any(), any(), any(), eq("COMMENT:123"));
    }

    @Test
    void targetRef里的id是postId而不是commentId() {
        // ⚠️ 放 commentId 的话客户端得先反查 postId 才跳得动（AC4 要落的是帖子详情 + 评论锚点）。
        listener.onContentMentioned(event(55L, List.of(2L)));
        ArgumentCaptor<String> ref = ArgumentCaptor.forClass(String.class);
        verify(notifications).sendWithCopy(anyLong(), any(), any(), any(), any(), any(),
                ref.capture());
        assertThat(ref.getValue()).isEqualTo("COMMENT:123").doesNotContain("55");
    }

    @Test
    void 只有一个通知类型而不是两个() {
        // AD-10 Rule 5 写的是「新增**一个**通知类型」；两个值意味着这个约束每多一种
        // 提及场景就得再重列一次 —— 而它已经出过四次事故。
        assertThat(Arrays.stream(NotificationType.values()).map(Enum::name))
                .doesNotContain("POST_MENTIONED", "COMMENT_MENTIONED", "MENTIONED");
    }

    @Test
    void 印尼语推送文案两套都在() throws IOException {
        // AC3：系统推送由后端下发印尼语。
        String id = Files.readString(resource("i18n/messages_id.properties"));
        assertThat(id).contains("notify.CONTENT_MENTIONED.POST.title")
                .contains("notify.CONTENT_MENTIONED.POST.body")
                .contains("notify.CONTENT_MENTIONED.COMMENT.title")
                .contains("notify.CONTENT_MENTIONED.COMMENT.body");
        // 两种场景的正文**必须不同** —— AC3 要求区分帖子提及与评论提及。
        assertThat(valueOf(id, "notify.CONTENT_MENTIONED.POST.body"))
                .isNotEqualTo(valueOf(id, "notify.CONTENT_MENTIONED.COMMENT.body"));
    }

    @Test
    void 印尼语推送文案里没有中文() throws IOException {
        // AC3：中文一个字都不进界面（仅内部对照，落在 zh_CN 那份里）。
        String id = Files.readString(resource("i18n/messages_id.properties"));
        for (String key : List.of("notify.CONTENT_MENTIONED.POST.title",
                "notify.CONTENT_MENTIONED.POST.body",
                "notify.CONTENT_MENTIONED.COMMENT.title",
                "notify.CONTENT_MENTIONED.COMMENT.body")) {
            assertThat(valueOf(id, key)).doesNotMatch(".*[\\u4e00-\\u9fa5].*");
        }
    }

    // ===== AC5：拉黑不发 =====

    @Test
    void actor与被at的人之间任一方向有拉黑就不发() {
        // AC5 原文是「**任一方向**」。写入侧洗过一次，但通知可能晚很久才发（挂起帖过审
        // 可能是几小时后），这中间 actor 完全可以把对方拉黑 —— 所以走双向的批量出口。
        when(hideRelations.hiddenEitherWay(anyLong(), anyCollection())).thenReturn(Set.of(2L));
        listener.onContentMentioned(event(null, List.of(2L, 3L)));
        verify(notifications, never()).sendWithCopy(eq(2L), any(), any(), any(), any(), any(), any());
        verify(notifications).sendWithCopy(eq(3L), any(), any(), any(), any(), any(), any());
    }

    @Test
    void 双向判定走的是统一出口的批量方法而不是逐个单向查() {
        // AD-7：拉黑判定只有一个出口；AD-6：批量。逐个 isHidden 两遍等于名单人数 × 2 次查询。
        listener.onContentMentioned(event(null, List.of(2L, 3L, 4L)));
        verify(hideRelations).hiddenEitherWay(eq(ACTOR), anyCollection());
    }

    @Test
    void 收件人拉黑了帖主时不发_否则点进去404() {
        // C 拉黑了帖主 A，B 在 A 的帖子下评论里 @ 了 C ——
        // 那条内容对 C 是 404（ContentDetailService 按 isHidden(viewer, post.authorId) 拦），
        // 通知照发等于「我拉黑了帖主」这件事被一条 @ 通知漏掉（code-review 2026-09-15）。
        when(hideRelations.isHidden(2L, AUTHOR)).thenReturn(true);
        listener.onContentMentioned(event(55L, List.of(2L, 3L)));
        verify(notifications, never()).sendWithCopy(eq(2L), any(), any(), any(), any(), any(), any());
        verify(notifications).sendWithCopy(eq(3L), any(), any(), any(), any(), any(), any());
    }

    @Test
    void 收件人恰好是帖主时不多判一次() {
        listener.onContentMentioned(event(55L, List.of(AUTHOR)));
        verify(hideRelations, never()).isHidden(AUTHOR, AUTHOR);
        verify(notifications).sendWithCopy(eq(AUTHOR), any(), any(), any(), any(), any(), any());
    }

    @Test
    void 内容作者拉黑了actor时第三方也不发_这条最容易漏() {
        // 这条才是容易漏的：被 @ 的人**不是**内容作者。A（作者）拉黑 B（actor）之后，
        // B 的评论因 R2 对所有人隐藏 —— 只判第一条判据时，被 B @ 的 C 会收到一条
        // 点进去什么都没有的通知，一对比就能推断出屏蔽机制存在。
        when(hideRelations.isHidden(AUTHOR, ACTOR)).thenReturn(true);
        listener.onContentMentioned(event(55L, List.of(2L)));
        verify(notifications, never()).sendWithCopy(anyLong(), any(), any(), any(), any(), any(), any());
    }

    // ===== 收件人集合 =====

    @Test
    void 每个被at的人各发一条() {
        listener.onContentMentioned(event(null, List.of(2L, 3L, 4L)));
        List<Long> got = new ArrayList<>();
        ArgumentCaptor<Long> to = ArgumentCaptor.forClass(Long.class);
        verify(notifications, org.mockito.Mockito.times(3))
                .sendWithCopy(to.capture(), any(), any(), any(), any(), any(), any());
        got.addAll(to.getAllValues());
        assertThat(got).containsExactly(2L, 3L, 4L);
    }

    @Test
    void 自己at自己不发() {
        listener.onContentMentioned(event(null, List.of(ACTOR, 2L)));
        verify(notifications, never()).sendWithCopy(eq(ACTOR), any(), any(), any(), any(), any(), any());
    }

    @Test
    void 正文提及时不多查一次收件人与作者的关系() {
        // 正文提及里 actor 就是作者，那一层已被双向批量判定覆盖。
        listener.onContentMentioned(new ContentMentionedEvent(123L, null, AUTHOR, AUTHOR,
                List.of(2L), AT));
        verify(hideRelations, never()).isHidden(anyLong(), anyLong());
    }

    @Test
    void 名单里重复的人只发一条() {
        listener.onContentMentioned(event(null, List.of(2L, 2L)));
        verify(notifications, org.mockito.Mockito.times(1))
                .sendWithCopy(eq(2L), any(), any(), any(), any(), any(), any());
    }

    @Test
    void 名单里的null不会炸() {
        listener.onContentMentioned(event(null, Arrays.asList(null, 2L)));
        verify(notifications, org.mockito.Mockito.times(1))
                .sendWithCopy(eq(2L), any(), any(), any(), any(), any(), any());
    }

    // ===== 辅助：读迁移 / 资源 =====

    private static Path resource(String relative) {
        return Path.of("src", "main", "resources").resolve(relative);
    }

    /**
     * 树里**最后一条**重建 {@code ck_notifications_type} 的迁移。
     *
     * <p>🔴 判据是「版本序最后那一条」而不是某个写死的文件名：该约束的改法是
     * 「DROP 掉再按完整清单 ADD 回去」，<b>谁排在版本序最后谁说了算</b>
     * （这正是四次事故的机理）。写死文件名的话，下次别人另起一条重建时本用例会失效。
     */
    private static Path lastRebuildMigration() throws IOException {
        Path dir = resource("db/migration");
        try (var files = Files.list(dir)) {
            return files.filter(p -> p.getFileName().toString().endsWith(".sql"))
                    .filter(MentionNotifyTest::rebuildsTypeConstraint)
                    .max(java.util.Comparator.comparing(MentionNotifyTest::migrationSortKey))
                    .orElseThrow(() -> new AssertionError("找不到任何重建 ck_notifications_type 的迁移"));
        }
    }

    private static boolean rebuildsTypeConstraint(Path p) {
        try {
            String sql = Files.readString(p);
            return sql.contains("ADD CONSTRAINT ck_notifications_type CHECK");
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Flyway 版本序的排序键。
     *
     * <p>序号制（{@code V104__}）与时间戳制（{@code V20260915_1614__}）混存，
     * 而时间戳制一律大于序号制（决策 E7 之后的新迁移都是时间戳）—— 按「位数更多的更大」
     * 就能把两制排在一起：位数相同再按字典序。
     */
    private static String migrationSortKey(Path p) {
        String name = p.getFileName().toString();
        String version = name.substring(1, name.indexOf("__")).replace("_", "");
        return String.format("%03d%s", version.length(), version);
    }

    private static Set<String> lastRebuiltTypeValues() throws IOException {
        String sql = Files.readString(lastRebuildMigration());
        int from = sql.indexOf("ADD CONSTRAINT ck_notifications_type CHECK");
        Set<String> out = new LinkedHashSet<>();
        Matcher m = Pattern.compile("'([A-Z][A-Z0-9_]*)'").matcher(sql.substring(from));
        while (m.find()) {
            out.add(m.group(1));
        }
        return out;
    }

    private static String valueOf(String properties, String key) {
        Matcher m = Pattern.compile("^" + Pattern.quote(key) + "=(.*)$", Pattern.MULTILINE)
                .matcher(properties);
        assertThat(m.find()).as("缺少文案键 " + key).isTrue();
        return m.group(1).trim();
    }
}
