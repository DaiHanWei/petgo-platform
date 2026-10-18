package com.tailtopia.admin.growth.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.tailtopia.admin.growth.service.AdminGrowthBatchService;
import com.tailtopia.admin.shared.web.HxRequest;
import com.tailtopia.support.TestMessages;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.ui.ConcurrentModel;

/** V1.4.0 ① Story 4.3 · AC1.4（L0）：批量端点门 = growth.user_adjust；成长体系页门含该码。 */
class AdminGrowthBatchAccessControlTest {

    private static AnnotationConfigApplicationContext ctx;
    private static AdminGrowthBatchController controller;

    @Configuration
    @EnableMethodSecurity
    static class TestConfig {
        @Bean
        AdminGrowthBatchController controller() {
            return new AdminGrowthBatchController(mock(AdminGrowthBatchService.class), TestMessages.real());
        }
    }

    @BeforeAll
    static void start() {
        ctx = new AnnotationConfigApplicationContext(TestConfig.class);
        controller = ctx.getBean(AdminGrowthBatchController.class);
    }

    @AfterAll
    static void stop() {
        ctx.close();
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private static void auth(String... authorities) {
        var t = new TestingAuthenticationToken("admin", "n/a", authorities);
        t.setAuthenticated(true);
        SecurityContextHolder.getContext().setAuthentication(t);
    }

    @Test
    void endpointsNeedGrowthUserAdjust() {
        auth("ROLE_ADMIN", "growth.config_edit", "growth.config_view");
        assertThatThrownBy(() -> controller.drawer(HxRequest.NONE, new ConcurrentModel()))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(controller::template).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> controller.preview("x", "1", "1", null, new ConcurrentModel(),
                new MockHttpServletResponse())).isInstanceOf(AccessDeniedException.class);

        auth("ROLE_ADMIN", "growth.user_adjust");
        assertThatCode(() -> controller.drawer(HxRequest.NONE, new ConcurrentModel())).doesNotThrowAnyException();
        assertThat(controller.template().getBody()).isNotEmpty();
    }

    @Test
    void gateConstantsAgree() {
        assertThat(AdminGrowthBatchController.GROWTH_ADJUST_AUTH).isEqualTo(AdminGrowthUserController.GROWTH_ADJUST_AUTH);
        assertThat(AdminGrowthConfigController.VIEW_AUTH).contains("'growth.user_adjust'");
        assertThat(AdminGrowthConfigController.EDIT_AUTH).doesNotContain("growth.user_adjust");
    }
}
