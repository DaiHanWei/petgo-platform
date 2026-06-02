package com.petgo.admin.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.petgo.admin.dto.SeedPostForm;
import com.petgo.admin.service.AdminContentService;
import com.petgo.admin.service.AdminUserDetails;
import com.petgo.content.domain.ContentType;
import com.petgo.content.dto.ContentPostResponse;
import com.petgo.shared.error.AppException;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ui.ConcurrentModel;
import org.springframework.ui.Model;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.BindingResult;

/** L0：后台页面视图名 + 种子发布成功/校验失败回显（AC1/AC2，纯控制器单测）。 */
class AdminWebControllerTest {

    private AdminContentService adminContentService;
    private com.petgo.admin.service.AdminModerationService adminModerationService;
    private AdminWebController controller;

    @BeforeEach
    void setUp() {
        adminContentService = mock(AdminContentService.class);
        adminModerationService = mock(com.petgo.admin.service.AdminModerationService.class);
        controller = new AdminWebController(adminContentService, adminModerationService);
    }

    private AdminUserDetails admin() {
        return new AdminUserDetails(99L, "ops@petgo", "{bcrypt}x");
    }

    private SeedPostForm form(ContentType type, String text) {
        SeedPostForm f = new SeedPostForm();
        f.setType(type);
        f.setText(text);
        return f;
    }

    @Test
    void loginAndDashboardAndSeedFormViews() {
        assertThat(controller.login()).isEqualTo("admin/login");

        Model m1 = new ConcurrentModel();
        assertThat(controller.dashboard(m1)).isEqualTo("admin/dashboard");
        assertThat(m1.getAttribute("active")).isEqualTo("dashboard");

        Model m2 = new ConcurrentModel();
        assertThat(controller.seedPostForm(m2)).isEqualTo("admin/seed-post");
        assertThat(m2.getAttribute("seedPostForm")).isInstanceOf(SeedPostForm.class);
        assertThat(m2.getAttribute("types")).isEqualTo(ContentType.values());
    }

    @Test
    void publishSeedSuccessClearsFormAndExposesPostId() {
        SeedPostForm f = form(ContentType.DAILY, "hello");
        f.setImageUrlsRaw("https://cdn/a.jpg\nhttps://cdn/b.jpg");
        BindingResult binding = new BeanPropertyBindingResult(f, "seedPostForm");
        when(adminContentService.publishSeed(anyLong(), eq(ContentType.DAILY), any(), eq("hello"), any()))
                .thenReturn(new ContentPostResponse(7L, ContentType.DAILY, null, "hello", null, null, Instant.now()));

        Model model = new ConcurrentModel();
        String view = controller.publishSeed(admin(), f, binding, model);

        assertThat(view).isEqualTo("admin/seed-post");
        assertThat(model.getAttribute("publishedId")).isEqualTo(7L);
        // 成功后表单清空
        assertThat(((SeedPostForm) model.getAttribute("seedPostForm")).getText()).isNull();
        // author 取自登录会话（99），多行 URL 拆成列表传下去
        verify(adminContentService).publishSeed(eq(99L), eq(ContentType.DAILY), any(),
                eq("hello"), eq(List.of("https://cdn/a.jpg", "https://cdn/b.jpg")));
    }

    @Test
    void bindingErrorsShortCircuitWithoutPublishing() {
        SeedPostForm f = form(null, "x");
        BindingResult binding = new BeanPropertyBindingResult(f, "seedPostForm");
        binding.rejectValue("type", "NotNull");

        Model model = new ConcurrentModel();
        String view = controller.publishSeed(admin(), f, binding, model);

        assertThat(view).isEqualTo("admin/seed-post");
        verify(adminContentService, org.mockito.Mockito.never())
                .publishSeed(anyLong(), any(), any(), any(), any());
    }

    @Test
    void serviceValidationFailureRendersInlineError() {
        SeedPostForm f = form(ContentType.GROWTH_MOMENT, "x");
        BindingResult binding = new BeanPropertyBindingResult(f, "seedPostForm");
        when(adminContentService.publishSeed(anyLong(), any(), any(), any(), any()))
                .thenThrow(AppException.validation("无法绑定该宠物档案"));

        Model model = new ConcurrentModel();
        String view = controller.publishSeed(admin(), f, binding, model);

        assertThat(view).isEqualTo("admin/seed-post");
        assertThat(binding.hasGlobalErrors()).isTrue();
    }
}
