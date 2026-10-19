package com.tailtopia.admin.petslot.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.tailtopia.admin.account.domain.AdminAccountType;
import com.tailtopia.admin.petslot.service.AdminPetSlotGrantService;
import com.tailtopia.admin.petslot.service.AdminPetSlotTabService;
import com.tailtopia.admin.service.AdminUserDetails;
import com.tailtopia.admin.shared.web.HxRequest;
import com.tailtopia.profile.slot.AdminSlotGrantResult;
import com.tailtopia.support.TestMessages;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.ui.ConcurrentModel;
import org.springframework.web.servlet.mvc.support.RedirectAttributesModelMap;

/**
 * L0：V1.4.0 multi-pet Story 4.3 code review #3 —— 补发 toast 的天数取<b>实际补发结果</b>：同一 token 重放时
 * 这次填了 30 天，服务返回首次补发的 7 天，toast / flash 必须报 7 天（不报假的「已补发 30 天」）。
 */
class AdminPetSlotControllerTest {

    private final AdminPetSlotGrantService grants = mock(AdminPetSlotGrantService.class);
    private final AdminPetSlotTabService tabs = mock(AdminPetSlotTabService.class);
    private final AdminPetSlotController controller = new AdminPetSlotController(grants, tabs, TestMessages.real());
    private final AdminUserDetails admin = new AdminUserDetails(900L, null, "ops@tailtopia.test", null,
            AdminAccountType.STAFF);

    private void replayOfSevenDays() {
        when(grants.grant(eq(42L), eq(30), anyString(), eq("tok-1"), eq(900L)))
                .thenReturn(new AdminSlotGrantResult(Instant.parse("2026-10-20T08:40:00Z"), true, 7));
    }

    @Test
    void htmxToastUsesTheGrantedDaysNotTheResubmittedOnes() {
        replayOfSevenDays();
        ConcurrentModel model = new ConcurrentModel();
        MockHttpServletResponse resp = new MockHttpServletResponse();

        String view = controller.grant(admin, 42L, "30", "r", "tok-1", new HxRequest(true, "user-drawer-err", null,
                null), model, resp, new RedirectAttributesModelMap());

        assertThat(view).isEqualTo("admin/fragments/drawer-users-petslots :: afterGrant");
        assertThat(String.valueOf(model.getAttribute("toast"))).contains("7").doesNotContain("30");
        assertThat(resp.getHeader("HX-Retarget")).isEqualTo("#user-petslots-panel");
        assertThat(resp.getHeader("HX-Reswap")).isEqualTo("outerHTML");
    }

    @Test
    void nonHtmxFlashUsesTheGrantedDaysToo() {
        replayOfSevenDays();
        RedirectAttributesModelMap flash = new RedirectAttributesModelMap();

        String view = controller.grant(admin, 42L, "30", "r", "tok-1", HxRequest.NONE, new ConcurrentModel(),
                new MockHttpServletResponse(), flash);

        assertThat(view).isEqualTo("redirect:/admin/users?open=42");
        assertThat(String.valueOf(flash.getFlashAttributes().get("notice"))).contains("7").doesNotContain("30");
    }
}
