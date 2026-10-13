package com.tailtopia.auth.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tailtopia.auth.domain.User;
import com.tailtopia.content.domain.ContentPost;
import com.tailtopia.content.domain.ContentType;
import com.tailtopia.content.repository.ContentPostRepository;
import com.tailtopia.social.service.UserHideRelationService;
import com.tailtopia.support.ApiIntegrationTest;
import java.util.List;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;

/**
 * L1：公开主页的关注字段（V1.3.4 follow · Story 1.2 · AC2 / AC3 / AC4）—— 端点级断言 JSON 键名。
 */
class PublicProfileFollowFieldsIntegrationTest extends ApiIntegrationTest {

    @Autowired
    private UserHideRelationService hideService;

    @Autowired
    private ContentPostRepository posts;

    private void follow(User viewer, User target) throws Exception {
        mvc.perform(put("/api/v1/users/" + target.getPublicToken() + "/follow")
                        .header(HttpHeaders.AUTHORIZATION, userBearer(viewer.getId())))
                .andExpect(status().isOk());
    }

    @Test
    void profileCarriesFollowFieldsWithStableJsonKeys() throws Exception {
        User a = newUser();
        User b = newUser();
        follow(a, b);

        mvc.perform(get("/api/v1/users/" + b.getId() + "/profile")
                        .header(HttpHeaders.AUTHORIZATION, userBearer(a.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userToken").value(Matchers.matchesPattern("[0-9a-zA-Z]{32}")))
                .andExpect(jsonPath("$.followerCount").value(1))
                .andExpect(jsonPath("$.followingCount").value(0))
                .andExpect(jsonPath("$.iFollow").value(true))
                .andExpect(jsonPath("$.followsMe").value(false));

        // 游客：两布尔恒在且为 false。
        mvc.perform(get("/api/v1/users/" + b.getId() + "/profile"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.iFollow").value(false))
                .andExpect(jsonPath("$.followsMe").value(false))
                .andExpect(jsonPath("$.followerCount").value(1));
    }

    /** AC3：B 被 A 拉黑后看 A 的主页 —— 帖子 / 获赞 0，关注 / 粉丝真实，iFollow 真实。 */
    @Test
    void blockedViewerSeesRealFollowCounts() throws Exception {
        User a = newUser();
        User b = newUser();
        posts.save(ContentPost.publish(a.getId(), ContentType.DAILY, null, "公开帖", List.of()));
        follow(b, a);
        follow(a, b);
        // A 拉黑 B：A→B 被同事务删除（AC1），B→A 保留 —— 故 B 看 A 时 followsMe=false、iFollow=true。
        hideService.block(a.getId(), b.getId());

        mvc.perform(get("/api/v1/users/" + a.getId() + "/profile")
                        .header(HttpHeaders.AUTHORIZATION, userBearer(b.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.postCount").value(0))
                .andExpect(jsonPath("$.likeCount").value(0))
                .andExpect(jsonPath("$.followerCount").value(1))
                .andExpect(jsonPath("$.iFollow").value(true))
                .andExpect(jsonPath("$.followsMe").value(false));
    }

    /** AC4 / D-8：封号按已注销处理；内容区空页；重新激活后恢复。 */
    @Test
    void deactivatedUserProfileIsDeactivatedAndRecovers() throws Exception {
        User a = newUser();
        User b = newUser();
        posts.save(ContentPost.publish(b.getId(), ContentType.DAILY, null, "公开帖", List.of()));
        User banned = users.findById(b.getId()).orElseThrow();
        banned.deactivate();
        users.save(banned);

        mvc.perform(get("/api/v1/users/" + b.getId() + "/profile")
                        .header(HttpHeaders.AUTHORIZATION, userBearer(a.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.isDeactivated").value(true))
                .andExpect(jsonPath("$.userToken").doesNotExist())
                .andExpect(jsonPath("$.nickname").doesNotExist());
        mvc.perform(get("/api/v1/users/" + b.getId() + "/posts"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isEmpty());
        // /pet 的封号收口由既有单测 PublicProfilePetBadgeTest.inactiveOwnerStill204AndBadgeNeverQueried 覆盖
        // （b 没有宠物档案，这里断言 204 证明不了封号分支）。

        User back = users.findById(b.getId()).orElseThrow();
        back.reactivate();
        users.save(back);
        mvc.perform(get("/api/v1/users/" + b.getId() + "/profile"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.isDeactivated").value(false))
                .andExpect(jsonPath("$.nickname").exists());
    }
}
