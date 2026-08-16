package com.tailtopia.notify.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.tailtopia.moderation.event.ReportResolvedEvent;
import com.tailtopia.notify.domain.NotificationType;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * L0：FR-51 举报处理回告的文案（Story 3.4）。
 *
 * <p><b>这条文案 2026-08-16 有意变更过</b>（PRD §6 定稿）：
 * 旧「感谢你的举报，我们已完成审核。」→ 新「你的举报已处理，感谢你帮助维护社区环境」。
 * <b>不是修 bug</b>；而且它是内容举报与账号举报<b>共用的同一条通知</b>，
 * 所以已经上线的内容举报回告也跟着变了 —— <b>有意为之</b>。
 *
 * <p>本测试钉住两件事：① 新文案确实发出去了；② <b>三处文案没有走散</b>。
 * 同一句话落在落库文案、推送本地化、App 的 ARB 三个地方，
 * 改一处漏一处的表现是「站内看到新的、推送收到旧的」—— 用户会觉得平台在自说自话。
 * （App 那一处在另一个代码库，这里只能覆盖后端两处，ARB 侧由 Flutter 的通知中心测试守着。）
 */
class ReportReviewedCopyTest {

    /** PRD §6 定稿那句。改它之前先确认产品真的又改了口径。 */
    private static final String EXPECTED_BODY_ZH = "你的举报已处理，感谢你帮助维护社区环境";
    private static final String EXPECTED_TITLE_ZH = "举报已处理";

    @Test
    void listenerSendsTheSignedOffCopy() {
        NotificationService notifications = mock(NotificationService.class);
        new ModerationNotifyListener(notifications)
                .onReportResolved(new ReportResolvedEvent(1L, 7L, Instant.now()));

        ArgumentCaptor<String> title = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(notifications).send(eq(7L), eq(NotificationType.REPORT_REVIEWED),
                title.capture(), body.capture(), anyString(), any());

        assertThat(title.getValue()).isEqualTo(EXPECTED_TITLE_ZH);
        assertThat(body.getValue()).isEqualTo(EXPECTED_BODY_ZH);
        // 旧文案不得复活。
        assertThat(body.getValue()).doesNotContain("已完成审核");
    }

    /** 推送那条路径（按收件人语言渲染）三份 properties 都要跟上，否则推送收到的还是旧话。 */
    @Test
    void pushCopyIsUpdatedInAllThreeLocales() throws IOException {
        assertThat(message("messages_zh_CN.properties", "notify.REPORT_REVIEWED.body"))
                .isEqualTo(EXPECTED_BODY_ZH);
        // 另外两种语言只断言「不再是旧话」——具体措辞待运营/法务定稿，不适合在测试里钉死字面。
        assertThat(message("messages_en.properties", "notify.REPORT_REVIEWED.body"))
                .doesNotContain("completed our review");
        assertThat(message("messages_id.properties", "notify.REPORT_REVIEWED.body"))
                .doesNotContain("menyelesaikan peninjauan");
    }

    private static String message(String file, String key) throws IOException {
        Properties props = new Properties();
        try (InputStream in = ReportReviewedCopyTest.class.getResourceAsStream("/i18n/" + file)) {
            assertThat(in).as(file + " 应存在").isNotNull();
            props.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        }
        String value = props.getProperty(key);
        assertThat(value).as(file + " 缺键 " + key).isNotNull();
        return value;
    }

    /** 只改文案：type / deepLinkType / targetRef 一概不动（AC5）。 */
    @Test
    void onlyTheCopyChangedNotTheRouting() {
        NotificationService notifications = mock(NotificationService.class);
        new ModerationNotifyListener(notifications)
                .onReportResolved(new ReportResolvedEvent(1L, 7L, Instant.now()));

        verify(notifications).send(anyLong(), eq(NotificationType.REPORT_REVIEWED),
                anyString(), anyString(), eq("REPORT_REVIEWED"), eq(null));
    }
}
