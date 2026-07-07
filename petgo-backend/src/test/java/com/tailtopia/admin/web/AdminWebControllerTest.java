package com.tailtopia.admin.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tailtopia.admin.dto.CreateVetForm;
import com.tailtopia.admin.dto.SeedPostForm;
import com.tailtopia.admin.service.AdminContentService;
import com.tailtopia.admin.service.AdminUserDetails;
import com.tailtopia.content.domain.ContentType;
import com.tailtopia.content.dto.ContentPostResponse;
import com.tailtopia.shared.error.AppException;
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
    private com.tailtopia.admin.service.AdminModerationService adminModerationService;
    private com.tailtopia.admin.service.AdminVetService adminVetService;
    private AdminWebController controller;

    @BeforeEach
    void setUp() {
        adminContentService = mock(AdminContentService.class);
        adminModerationService = mock(com.tailtopia.admin.service.AdminModerationService.class);
        adminVetService = mock(com.tailtopia.admin.service.AdminVetService.class);
        controller = new AdminWebController(adminContentService, adminModerationService, adminVetService);
    }

    private AdminUserDetails admin() {
        // Story 1.1：(adminAccountId, operatorUserId=官方内容作者 users.id, email, passwordHash, accountType)
        return new AdminUserDetails(7L, 99L, "ops@petgo", "{bcrypt}x",
                com.tailtopia.admin.account.domain.AdminAccountType.SUPER_ADMIN);
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

    // ===== 兽医开户弹窗：校验失败/业务失败 → 置 createVetModalOpen（整页重渲染时弹窗自动重开回显）=====

    private CreateVetForm vetForm() {
        CreateVetForm f = new CreateVetForm();
        f.setDisplayName("建号医生");
        f.setUsername("v@vet.test");
        f.setPassword("Secret#1");
        f.setContactPhone("+62-811");
        return f;
    }

    @Test
    void createVetBindingErrorReopensModal() {
        CreateVetForm f = vetForm();
        BindingResult binding = new BeanPropertyBindingResult(f, "createVetForm");
        binding.rejectValue("username", "NotBlank");
        Model model = new ConcurrentModel();

        String view = controller.createVet(admin(), f, binding, model);

        assertThat(view).isEqualTo("admin/vets");
        assertThat(model.getAttribute("createVetModalOpen")).isEqualTo(true);
        verify(adminVetService, org.mockito.Mockito.never())
                .create(any(), any(), any(), any(), anyLong());
    }

    @Test
    void createVetServiceFailureReopensModalWithGlobalError() {
        CreateVetForm f = vetForm();
        BindingResult binding = new BeanPropertyBindingResult(f, "createVetForm");
        when(adminVetService.create(any(), any(), any(), any(), anyLong()))
                .thenThrow(AppException.validation("邮箱已被占用"));
        Model model = new ConcurrentModel();

        String view = controller.createVet(admin(), f, binding, model);

        assertThat(view).isEqualTo("admin/vets");
        assertThat(model.getAttribute("createVetModalOpen")).isEqualTo(true);
        assertThat(binding.hasGlobalErrors()).isTrue();
    }

    @Test
    void createVetSuccessExposesIdAndKeepsModalClosed() {
        CreateVetForm f = vetForm();
        BindingResult binding = new BeanPropertyBindingResult(f, "createVetForm");
        when(adminVetService.create(eq("建号医生"), eq("v@vet.test"), eq("Secret#1"),
                eq("+62-811"), anyLong())).thenReturn(42L);
        Model model = new ConcurrentModel();

        String view = controller.createVet(admin(), f, binding, model);

        assertThat(view).isEqualTo("admin/vets");
        assertThat(model.getAttribute("createdVetId")).isEqualTo(42L);
        assertThat(model.getAttribute("createVetModalOpen")).isNull(); // 成功不重开弹窗
    }

    /** 回归：无关联内容作者身份的账号（operatorUserId=null，如 STAFF/纯 Lark）发种子内容
     *  应内联报错而非 500（getUserId() 会抛 IllegalStateException）。 */
    @Test
    void publishSeedWithoutOperatorUserIdRendersErrorNot500() {
        AdminUserDetails noOperator = new AdminUserDetails(7L, null, "staff@petgo", "{bcrypt}x",
                com.tailtopia.admin.account.domain.AdminAccountType.STAFF);
        SeedPostForm f = form(ContentType.DAILY, "hello");
        BindingResult binding = new BeanPropertyBindingResult(f, "seedPostForm");
        Model model = new ConcurrentModel();

        String view = controller.publishSeed(noOperator, f, binding, model);

        assertThat(view).isEqualTo("admin/seed-post");
        assertThat(binding.hasGlobalErrors()).isTrue();
        verify(adminContentService, org.mockito.Mockito.never())
                .publishSeed(anyLong(), any(), any(), any(), any()); // 守卫拦在调用前
    }
}
