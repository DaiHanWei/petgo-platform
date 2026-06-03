package com.petgo.vet.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.petgo.support.ApiIntegrationTest;
import com.petgo.support.VetTestSupport;
import com.petgo.vet.domain.VetAccount;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

/**
 * L1 集成：{@link VetPresenceController}（在线态 4 端点）+ {@link BannedVetFilter} 封禁即生效。
 *
 * <ul>
 *   <li>{@code PUT /api/v1/vet/online-status} 上/下线（写 Redis 在线集合）。</li>
 *   <li>{@code GET /api/v1/vet/online-status} 读自身在线态。</li>
 *   <li>{@code POST /api/v1/vet/heartbeat} 心跳续期。</li>
 *   <li>{@code POST /api/v1/vet/logout} 登出即离线（204）。</li>
 * </ul>
 *
 * <p>每个端点至少一条 user→403 / 缺 token→401 / active vet→2xx。BANNED vet token 打任意
 * vet 端点 → BannedVetFilter 401（账号停用 ProblemDetail）。
 */
class VetPresenceControllerEndpointTest extends ApiIntegrationTest {

    @Autowired
    private VetTestSupport vets;

    // ===== 上/下线 + 读回 =====

    @Test
    void setOnlineThenRead_reflectsOnlineStatus() throws Exception {
        VetAccount vet = vets.newActiveVet("在线医生");

        mvc.perform(put("/api/v1/vet/online-status")
                        .header("Authorization", vetBearer(vet.getId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("online", true))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.online").value(true))
                .andExpect(jsonPath("$.status").value("ONLINE"));

        mvc.perform(get("/api/v1/vet/online-status").header("Authorization", vetBearer(vet.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.online").value(true))
                .andExpect(jsonPath("$.status").value("ONLINE"));
    }

    @Test
    void setOffline_reflectsOfflineStatus() throws Exception {
        VetAccount vet = vets.newActiveVet("下线医生");

        // 先上线再下线，验证状态翻转
        mvc.perform(put("/api/v1/vet/online-status")
                        .header("Authorization", vetBearer(vet.getId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("online", true))))
                .andExpect(status().isOk());

        mvc.perform(put("/api/v1/vet/online-status")
                        .header("Authorization", vetBearer(vet.getId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("online", false))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.online").value(false))
                .andExpect(jsonPath("$.status").value("OFFLINE"));
    }

    @Test
    void getOnlineStatus_defaultOffline() throws Exception {
        VetAccount vet = vets.newActiveVet("默认医生");
        // 从未上线 → OFFLINE
        mvc.perform(get("/api/v1/vet/online-status").header("Authorization", vetBearer(vet.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.online").value(false))
                .andExpect(jsonPath("$.status").value("OFFLINE"));
    }

    @Test
    void setOnline_missingBody_is422() throws Exception {
        VetAccount vet = vets.newActiveVet("空体医生");
        // online @NotNull → Bean 校验失败 → 422 ProblemDetail
        mvc.perform(put("/api/v1/vet/online-status")
                        .header("Authorization", vetBearer(vet.getId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of())))
                .andExpect(status().isUnprocessableEntity());
    }

    // ===== 心跳 =====

    @Test
    void heartbeat_keepsOnline() throws Exception {
        VetAccount vet = vets.newActiveVet("心跳医生");
        // 先上线，心跳续期后仍 ONLINE
        mvc.perform(put("/api/v1/vet/online-status")
                        .header("Authorization", vetBearer(vet.getId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("online", true))))
                .andExpect(status().isOk());

        mvc.perform(post("/api/v1/vet/heartbeat").header("Authorization", vetBearer(vet.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.online").value(true))
                .andExpect(jsonPath("$.status").value("ONLINE"));
    }

    // ===== 登出 =====

    @Test
    void logout_isNoContentAndGoesOffline() throws Exception {
        VetAccount vet = vets.newActiveVet("登出医生");
        mvc.perform(put("/api/v1/vet/online-status")
                        .header("Authorization", vetBearer(vet.getId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("online", true))))
                .andExpect(status().isOk());

        mvc.perform(post("/api/v1/vet/logout").header("Authorization", vetBearer(vet.getId())))
                .andExpect(status().isNoContent());

        // 登出后读回 → OFFLINE
        mvc.perform(get("/api/v1/vet/online-status").header("Authorization", vetBearer(vet.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.online").value(false));
    }

    // ===== 角色门控（每端点 user→403 / 缺 token→401）=====

    @Test
    void userToken_isForbidden403_onAllEndpoints() throws Exception {
        var user = newUser();
        String bearer = userBearer(user.getId());

        mvc.perform(put("/api/v1/vet/online-status")
                        .header("Authorization", bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("online", true))))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/vet/online-status").header("Authorization", bearer))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/vet/heartbeat").header("Authorization", bearer))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/vet/logout").header("Authorization", bearer))
                .andExpect(status().isForbidden());
    }

    @Test
    void missingToken_isUnauthorized401_onAllEndpoints() throws Exception {
        mvc.perform(put("/api/v1/vet/online-status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("online", true))))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/vet/online-status")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/vet/heartbeat")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/vet/logout")).andExpect(status().isUnauthorized());
    }

    // ===== BannedVetFilter：封禁即生效 =====

    @Test
    void bannedVet_isUnauthorized401_byBannedVetFilter() throws Exception {
        VetAccount banned = vets.newBannedVet("被封医生");
        // BANNED vet 持合法 VET JWT，但 BannedVetFilter 认证后授权前查 status → 401 踢下线
        mvc.perform(get("/api/v1/vet/online-status").header("Authorization", vetBearer(banned.getId())))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.type").value("https://petgo/errors/account-disabled"));
    }

    @Test
    void nonexistentVet_isUnauthorized401_byBannedVetFilter() throws Exception {
        // DB 无此 vet 行 → BannedVetFilter isActive=false → 401（不存在等同停用）
        long ghostVetId = 99_000_000_000L;
        mvc.perform(get("/api/v1/vet/online-status").header("Authorization", vetBearer(ghostVetId)))
                .andExpect(status().isUnauthorized());
    }
}
