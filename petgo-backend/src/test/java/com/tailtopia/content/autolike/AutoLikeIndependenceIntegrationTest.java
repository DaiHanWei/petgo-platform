package com.tailtopia.content.autolike;

import static org.assertj.core.api.Assertions.assertThat;

import com.tailtopia.content.autocomment.AutoCommentJob;
import com.tailtopia.content.autocomment.AutoCommentService;
import com.tailtopia.support.ApiIntegrationTest;
import java.lang.reflect.Constructor;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * L1 · V1.5.0 likes Story 2.3 AC6（A-AC3）：自动点赞与自动评论互不依赖——两边 Bean 都在，且自动点赞的
 * 计划 / 派发 / 定时入口不注入任何自动评论的 Service / Job（只共用无状态的 {@code VirtualIdentityPicker}）。
 * 一边抛异常不影响另一边，因为两边各自 try/catch、各自的调度方法、各自的锁与表。
 */
class AutoLikeIndependenceIntegrationTest extends ApiIntegrationTest {

    @Autowired
    private AutoCommentJob autoCommentJob;
    @Autowired
    private AutoCommentService autoCommentService;
    @Autowired
    private AutoLikeJob autoLikeJob;
    @Autowired
    private AutoLikeDispatcher autoLikeDispatcher;

    @Test
    void beansExistAndAutoLikeDoesNotDependOnAutoComment() {
        assertThat(autoCommentJob).isNotNull();
        assertThat(autoCommentService).isNotNull();
        assertThat(autoLikeJob).isNotNull();
        assertThat(autoLikeDispatcher).isNotNull();
        for (Class<?> c : new Class<?>[] {AutoLikeJob.class, AutoLikePlanner.class, AutoLikeDispatcher.class}) {
            for (Constructor<?> ctor : c.getDeclaredConstructors()) {
                assertThat(Arrays.stream(ctor.getParameterTypes()))
                        .noneMatch(t -> t == AutoCommentService.class || t == AutoCommentJob.class);
            }
        }
    }

    @Test
    void autoLikeRunsWhileAutoCommentFails() {
        // 自动评论在 dev（GEMINI stub）下跳过 / 失败都不影响派发 tick 正常返回。
        autoCommentJob.morning();
        autoLikeDispatcher.tick();
        autoLikeJob.morning();
    }
}
