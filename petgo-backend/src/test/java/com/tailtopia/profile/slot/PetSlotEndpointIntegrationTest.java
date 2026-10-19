package com.tailtopia.profile.slot;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tailtopia.auth.domain.User;
import com.tailtopia.growth.domain.GrowthLevel;
import com.tailtopia.growth.domain.GrowthTier;
import com.tailtopia.growth.service.GrowthQueryService;
import com.tailtopia.support.ApiIntegrationTest;
import com.tailtopia.support.VetTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/** L1：V1.4.0 multi-pet Story 1.4 · AC6 —— {@code GET /pet-slots/me} 鉴权（游客 401、兽医 403）与 Lv1 / Lv5 取值。 */
class PetSlotEndpointIntegrationTest extends ApiIntegrationTest {

    @MockitoBean
    private GrowthQueryService growth;

    /** 兽医 token 经 BannedVetFilter 查 vet 行：不存在 / 非 ACTIVE → 401。要验「角色拦截 403」必须先造真兽医，不能拿任意 id 当 vetId。 */
    @Autowired
    private VetTestSupport vets;

    @Test
    void guestIs401() throws Exception {
        mvc.perform(get("/api/v1/pet-slots/me")).andExpect(status().isUnauthorized());
    }

    @Test
    void vetIs403() throws Exception {
        mvc.perform(get("/api/v1/pet-slots/me").header(HttpHeaders.AUTHORIZATION, vetBearer(vets.newActiveVet("pet-slot-endpoint-it").getId())))
                .andExpect(status().isForbidden());
    }

    @Test
    void lv1Overview() throws Exception {
        User u = newUser();
        when(growth.getLevel(u.getId())).thenReturn(GrowthLevel.of(1, GrowthTier.NEWBIE));
        mvc.perform(get("/api/v1/pet-slots/me").header(HttpHeaders.AUTHORIZATION, userBearer(u.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.cap").value(5))
                .andExpect(jsonPath("$.defaultSlots").value(1))
                .andExpect(jsonPath("$.levelSlots").value(0))
                .andExpect(jsonPath("$.rentalSlots").value(0))
                .andExpect(jsonPath("$.petCount").value(0))
                .andExpect(jsonPath("$.rentable").value(4))
                .andExpect(jsonPath("$.canRent").value(true))
                .andExpect(jsonPath("$.nextLevelSlot").value(5))
                .andExpect(jsonPath("$.unitPrice").value(5000))
                .andExpect(jsonPath("$.days").value(30));
    }

    @Test
    void lv5Overview() throws Exception {
        User u = newUser();
        when(growth.getLevel(u.getId())).thenReturn(GrowthLevel.of(5, GrowthTier.NEWBIE));
        mvc.perform(get("/api/v1/pet-slots/me").header(HttpHeaders.AUTHORIZATION, userBearer(u.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.levelSlots").value(1))
                .andExpect(jsonPath("$.nextLevelSlot").value(10));
    }
}
