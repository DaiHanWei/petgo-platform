package com.tailtopia.content.autocomment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tailtopia.auth.domain.User;
import com.tailtopia.shared.ai.PetCommentGenerator;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/** 【stag 分支专属】自动评论测试工具：汇总口径与 stub 闸。 */
class StagAutoCommentToolTest {

    private final AutoCommentService service = mock(AutoCommentService.class);
    private final AutoCommentLogRepository logs = mock(AutoCommentLogRepository.class);
    private final PetCommentGenerator generator = mock(PetCommentGenerator.class);
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final StagAutoCommentTool tool =
            new StagAutoCommentTool(service, logs, generator, new AutoCommentProperties(), jdbc);

    private static AutoCommentLog log(long postId, Long virtualId, String text, String skipReason, String error) {
        AutoCommentLog l = AutoCommentLog.forPost(postId);
        l.beginAttempt();
        l.setVirtualUserId(virtualId);
        l.setGeneratedText(text);
        l.setSkipReason(skipReason);
        l.setErrorCode(error);
        return l;
    }

    @Test
    void summarisesPostedSkippedByReasonAndFailed() {
        when(generator.live()).thenReturn(true);
        when(jdbc.queryForList(anyString(), eq(Long.class), eq(2), eq(10))).thenReturn(List.of(1L, 2L, 3L, 4L, 5L));
        User budi = mock(User.class);
        when(budi.getId()).thenReturn(9L);
        when(budi.getNickname()).thenReturn("Budi");
        when(service.virtualPool()).thenReturn(List.of(budi));
        when(service.processOne(eq(1L), any())).thenReturn(AutoCommentStatus.POSTED);
        when(service.processOne(eq(2L), any())).thenReturn(AutoCommentStatus.AI_SKIPPED);
        when(service.processOne(eq(3L), any())).thenReturn(AutoCommentStatus.NO_IMAGE);
        when(service.processOne(eq(4L), any())).thenReturn(AutoCommentStatus.FAILED);
        when(service.processOne(eq(5L), any())).thenReturn(null);
        when(logs.findByPostId(1L)).thenReturn(Optional.of(log(1, 9L, "Lucu! Namanya siapa?", null, null)));
        when(logs.findByPostId(2L)).thenReturn(Optional.of(log(2, 9L, null, "没有宠物", null)));
        when(logs.findByPostId(3L)).thenReturn(Optional.of(log(3, null, null, null, null)));
        when(logs.findByPostId(4L)).thenReturn(Optional.of(log(4, 9L, null, null, "GEMINI")));

        StagAutoCommentTool.Result r = tool.run(10);

        assertThat(r.aiLive()).isTrue();
        assertThat(r.total()).isEqualTo(5);
        assertThat(r.posted()).isEqualTo(1);
        assertThat(r.failed()).isEqualTo(1);
        assertThat(r.skipped()).isEqualTo(3);
        assertThat(r.skippedByStatus()).containsEntry("AI_SKIPPED", 1L).containsEntry("NO_IMAGE", 1L)
                .containsEntry("ALREADY_COMMENTED", 1L);
        assertThat(r.items().get(0).virtualNickname()).isEqualTo("Budi");
        assertThat(r.items().get(0).comment()).isEqualTo("Lucu! Namanya siapa?");
        assertThat(r.items().get(1).reason()).isEqualTo("没有宠物");
        assertThat(r.items().get(3).reason()).isEqualTo("GEMINI");
    }

    @Test
    void stubAiRunsNothing() {
        when(generator.live()).thenReturn(false);

        StagAutoCommentTool.Result r = tool.run(10);

        assertThat(r.aiLive()).isFalse();
        verify(service, never()).processOne(any(Long.class), any());
    }
}
