package com.tailtopia.auth.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tailtopia.auth.domain.PetStatus;
import com.tailtopia.auth.domain.User;
import com.tailtopia.support.ApiIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

/**
 * {@link MiniProfileController} 端点集成测试：
 * {@code GET /api/v1/users/{userId}/mini-profile}（只读、游客可见，无登录要求）。
 *
 * <p>注意（实测行为）：不存在的 userId <b>不返回 404</b>——{@code AccountQueryService.findAuthorViews}
 * 对缺失/注销 id 统一返回匿名投影，故 controller 走 {@code deactivated()} 分支返回
 * {@code isDeactivated=true}（nickname/avatar=null），不暴露「曾否存在」（NFR-8）。
 */
class MiniProfileControllerEndpointTest extends ApiIntegrationTest {

    /** 正常路径：存在的有效用户 → 200 + nickname/avatar/postCount + isDeactivated=false。游客无 token 即可读。 */
    @Test
    void miniProfile_existingUser_isGuestReadable() throws Exception {
        User u = newUser(PetStatus.HAS_PET);

        mvc.perform(get("/api/v1/users/{id}/mini-profile", u.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nickname").value(u.getNickname()))
                .andExpect(jsonPath("$.isDeactivated").value(false))
                .andExpect(jsonPath("$.postCount").value(0)); // 新用户无已发布内容
    }

    /** 游客可见性显式验证：完全不带 Authorization 头也返回 200。 */
    @Test
    void miniProfile_noToken_returns200() throws Exception {
        User u = newUser(PetStatus.HAS_PET);
        mvc.perform(get("/api/v1/users/{id}/mini-profile", u.getId())
                        .header(HttpHeaders.ACCEPT, "application/json"))
                .andExpect(status().isOk());
    }

    /**
     * 不存在的 userId：返回 200 + {@code isDeactivated=true}、nickname/avatar 为 null（匿名化），
     * 而非 404——验证不泄露身份信息的设计。
     */
    @Test
    void miniProfile_nonexistentUser_returnsDeactivatedNot404() throws Exception {
        long ghostId = 9_000_000_000L + SEQ.incrementAndGet(); // 几乎不可能存在的 id

        mvc.perform(get("/api/v1/users/{id}/mini-profile", ghostId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.isDeactivated").value(true))
                .andExpect(jsonPath("$.nickname").doesNotExist())   // NON_NULL 序列化省略 null
                .andExpect(jsonPath("$.avatarUrl").doesNotExist());
    }
}
