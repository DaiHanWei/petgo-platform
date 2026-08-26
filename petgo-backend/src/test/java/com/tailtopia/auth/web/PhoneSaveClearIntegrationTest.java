package com.tailtopia.auth.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tailtopia.auth.domain.User;
import com.tailtopia.support.ApiIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

/**
 * L1：手机号的保存 / 清空 / 校验（V1.1.6 Story 7.1 · FR-70）。
 *
 * <p>⚠️ 本 story 交付的界面**暂时没有入口**（软引导与设置页入口属 7-2），
 * 所以这里直接打接口。
 */
class PhoneSaveClearIntegrationTest extends ApiIntegrationTest {

    private String patchMe(User actor, String bodyJson) throws Exception {
        return mvc.perform(patch("/api/v1/me")
                        .header(HttpHeaders.AUTHORIZATION, userBearer(actor.getId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bodyJson))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    private String phoneOf(long userId) {
        return users.findById(userId).map(User::getPhone).orElse(null);
    }

    /** 常见写法存得进去，且**归一成同一形态**。 */
    @Test
    void commonWritingsAreSavedInACanonicalForm() throws Exception {
        User u = newUser();

        patchMe(u, "{\"phone\":\"0812-3456-7890\"}");

        assertThat(phoneOf(u.getId())).isEqualTo("+6281234567890");
    }

    /** 格式不对 → 422，且**不落库**。 */
    @Test
    void badFormatIsRejectedAndNothingIsStored() throws Exception {
        User u = newUser();

        mvc.perform(patch("/api/v1/me")
                        .header(HttpHeaders.AUTHORIZATION, userBearer(u.getId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phone\":\"12345\"}"))
                .andExpect(status().isUnprocessableEntity());

        assertThat(phoneOf(u.getId())).isNull();
    }

    /**
     * 🔴 **「没传」不等于「传了空」**。
     *
     * <p>这是本 story 最容易做错的一处：接口是部分更新（null = 不动它），
     * 若清空也用 null 表达，就永远分不清「我没改手机号」和「我要删掉手机号」——
     * 撤回权会**静默落空**（用户清空保存、提示说成功，实际没删）。
     */
    @Test
    void omittingThePhoneLeavesItUntouched() throws Exception {
        User u = newUser();
        patchMe(u, "{\"phone\":\"081234567890\"}");
        assertThat(phoneOf(u.getId())).isNotNull();

        // 只改昵称、完全不提手机号
        patchMe(u, "{\"nickname\":\"Budi\"}");

        assertThat(phoneOf(u.getId()))
                .as("没传 = 不动它")
                .isEqualTo("+6281234567890");
    }

    /** 🛡 传空串 = **清空（撤回）**，写回空值。 */
    @Test
    void emptyStringClearsThePhone() throws Exception {
        User u = newUser();
        patchMe(u, "{\"phone\":\"081234567890\"}");
        assertThat(phoneOf(u.getId())).isNotNull();

        patchMe(u, "{\"phone\":\"\"}");

        assertThat(phoneOf(u.getId())).as("留空保存 = 撤回").isNull();
    }

    /** 全是空白也按清空处理（用户按了几下空格再保存，意图是一样的）。 */
    @Test
    void whitespaceOnlyAlsoClears() throws Exception {
        User u = newUser();
        patchMe(u, "{\"phone\":\"081234567890\"}");

        patchMe(u, "{\"phone\":\"   \"}");

        assertThat(phoneOf(u.getId())).isNull();
    }

    /**
     * 清空后回到「未填写」分组 —— 后台的催填筛选按"该列为空"取，
     * 所以只要写回空值，这条就自动成立。这里把它钉住。
     */
    @Test
    void clearedUserFallsBackIntoTheNotFilledGroup() throws Exception {
        User u = newUser();
        patchMe(u, "{\"phone\":\"081234567890\"}");
        patchMe(u, "{\"phone\":\"\"}");

        assertThat(phoneOf(u.getId())).isNull(); // = 后台「未填写」
    }

    /** /me 聚合视图给本人下发完整号码（脱敏属显示层，放客户端做）。 */
    @Test
    void meReturnsTheFullNumberToItsOwner() throws Exception {
        User u = newUser();
        String body = patchMe(u, "{\"phone\":\"081234567890\"}");

        assertThat(body).contains("\"phone\":\"+6281234567890\"");
    }
}
