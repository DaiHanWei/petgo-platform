package com.tailtopia.admin.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tailtopia.admin.dto.CreateVetForm;
import com.tailtopia.admin.dto.SeedPostForm;
import com.tailtopia.admin.service.AdminContentService;
import com.tailtopia.admin.service.AdminUserDetails;
import com.tailtopia.admin.virtual.service.AdminVirtualAccountService;
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
    private AdminVirtualAccountService virtualAccountService;
    private com.tailtopia.admin.virtual.service.AdminPublishIdentityService identities;
    private AdminWebController controller;

    @BeforeEach
    void setUp() {
        adminContentService = mock(AdminContentService.class);
        adminModerationService = mock(com.tailtopia.admin.service.AdminModerationService.class);
        adminVetService = mock(com.tailtopia.admin.service.AdminVetService.class);
        virtualAccountService = mock(AdminVirtualAccountService.class);
        when(virtualAccountService.list()).thenReturn(List.of());
        identities = mock(com.tailtopia.admin.virtual.service.AdminPublishIdentityService.class);
        when(identities.selectableIdentities()).thenReturn(List.of());
        controller = new AdminWebController(adminContentService, adminModerationService, adminVetService,
                mock(com.tailtopia.admin.dashboard.service.AdminDashboardService.class), virtualAccountService,
                identities);
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
        // 🔴 V1.1.6 Story 12.2：作者来自**表单**（发布身份池），不再写死为登录会话关联的作者。
        f.setAuthorUserId(4242L);
        f.setImageUrlsRaw("https://cdn/a.jpg\nhttps://cdn/b.jpg");
        f.setImageSizesRaw("1200x900\n800x800");
        BindingResult binding = new BeanPropertyBindingResult(f, "seedPostForm");
        // ⚠️ V1.1.6 Story 14.1 加了第 8 个参数（关联物种）。桩没跟上时
        //    Mockito 返回 null，表现是控制器里 NPE —— 看起来像被测代码坏了。
        when(adminContentService.publishSeed(anyLong(), eq(ContentType.DAILY), any(), eq("hello"),
                any(), any(), anyBoolean(), any()))
                .thenReturn(new ContentPostResponse(7L, ContentType.DAILY, null, "hello", null, null, Instant.now()));

        Model model = new ConcurrentModel();
        String view = controller.publishSeed(admin(), f, binding, model);

        assertThat(view).isEqualTo("admin/seed-post");
        assertThat(model.getAttribute("publishedId")).isEqualTo(7L);
        // 成功后表单清空
        assertThat(((SeedPostForm) model.getAttribute("seedPostForm")).getText()).isNull();
        // author 取自**表单**（4242），多行 URL 拆成列表传下去，尺寸同序等长一起带上
        verify(adminContentService).publishSeed(eq(4242L), eq(ContentType.DAILY), any(),
                eq("hello"), eq(List.of("https://cdn/a.jpg", "https://cdn/b.jpg")),
                eq(List.of(new com.tailtopia.content.domain.ImageSize(1200, 900),
                        new com.tailtopia.content.domain.ImageSize(800, 800))),
                anyBoolean(), any());
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
                .publishSeed(anyLong(), any(), any(), any(), any(), any(), anyBoolean(), any());
    }

    @Test
    void serviceValidationFailureRendersInlineError() {
        SeedPostForm f = form(ContentType.GROWTH_MOMENT, "x");
        f.setAuthorUserId(4242L);
        BindingResult binding = new BeanPropertyBindingResult(f, "seedPostForm");
        when(adminContentService.publishSeed(anyLong(), any(), any(), any(), any(), any(),
                anyBoolean(), any()))
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

    /**
     * 🔴 <b>原用例「无关联内容作者身份的账号发种子应内联报错」已随 V1.1.6 Story 12.2 作废</b>。
     *
     * <p>那条守的是"发布身份取自后台账号"这个前提 —— 而本 story 恰恰**推翻了这个前提**：
     * 作者改为从发布身份池里选，所以 STAFF / 纯 Lark 账号（没有关联作者身份）现在照样能发。
     * 保留它会变成"钉住一个已经不该存在的限制"。
     *
     * <p>换成守新的那条边界：**没选发布账号 ⇒ 内联报错、不发布、且不 500**。
     * ⚠️ 500 这一半不是多余的：{@code authorUserId} 是 {@code Long}，
     * 拆箱成 {@code long} 时若为 null 就是 NPE ⇒ 白屏，而运营只会看到"系统错误"。
     */
    @Test
    void publishSeedWithoutAuthorRendersInlineErrorNot500() {
        AdminUserDetails staffWithoutAuthorIdentity = new AdminUserDetails(7L, null, "staff@petgo",
                "{bcrypt}x", com.tailtopia.admin.account.domain.AdminAccountType.STAFF);
        SeedPostForm f = form(ContentType.DAILY, "hello"); // 刻意不 setAuthorUserId
        BindingResult binding = new BeanPropertyBindingResult(f, "seedPostForm");
        Model model = new ConcurrentModel();

        String view = controller.publishSeed(staffWithoutAuthorIdentity, f, binding, model);

        assertThat(view).isEqualTo("admin/seed-post");
        assertThat(binding.hasGlobalErrors()).isTrue();
        verify(adminContentService, org.mockito.Mockito.never())
                .publishSeed(anyLong(), any(), any(), any(), any(), any(), anyBoolean(), any());
    }
}
