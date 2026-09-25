package com.tailtopia.admin.dailyreport;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Constructor;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * L0：日报模块的 Spring 装配守卫（2026-09-25 stag 启动失败的回归）。
 *
 * <p>多个构造器（正式用 + 测试注入用）却不标 {@code @Autowired}，Spring 会退回找无参构造、
 * 找不到就整个应用起不来 —— 编译与单测都照过，只在真启动时才炸。
 */
class DailyReportWiringTest {

    @Test
    @DisplayName("🔴 有多个构造器的 Bean 必须恰好一个标 @Autowired")
    void multiConstructorBeansDeclareWhichOneSpringUses() {
        for (Class<?> c : List.of(LarkWebhookClient.class, DailyReportService.class, DailyReportQuery.class,
                DailyReportJob.class, AdminDailyReportController.class)) {
            Constructor<?>[] ctors = c.getDeclaredConstructors();
            if (ctors.length > 1) {
                long marked = Arrays.stream(ctors).filter(k -> k.isAnnotationPresent(Autowired.class)).count();
                assertThat(marked).as(c.getSimpleName() + " 有 " + ctors.length + " 个构造器，必须恰好一个 @Autowired")
                        .isEqualTo(1);
            }
        }
    }
}
