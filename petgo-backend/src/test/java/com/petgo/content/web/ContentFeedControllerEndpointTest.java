package com.petgo.content.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.petgo.auth.domain.PetStatus;
import com.petgo.auth.domain.User;
import com.petgo.content.domain.ContentPost;
import com.petgo.content.domain.ContentType;
import com.petgo.content.repository.ContentPostRepository;
import com.petgo.content.service.FeedService;
import com.petgo.profile.domain.PetProfile;
import com.petgo.profile.repository.PetProfileRepository;
import com.petgo.support.ApiIntegrationTest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;

/**
 * L1 集成：Feed 读取端点 {@code GET /api/v1/content-posts}（{@link ContentFeedController}）。
 *
 * <p>覆盖：游客 GET 200（空/非空）；游标分页（首批 + cursor 第二批不重不漏）；分类过滤；
 * 宠物状态硬过滤（B 不含 GROWTH_MOMENT）；注销/不存在作者匿名化（authorDeleted=true + 昵称 null）。
 *
 * <p>说明：Feed 全平台可见，无法保证「空」（其他测试也写库）。因此分页/过滤断言均围绕<b>本测试自造、
 * 带唯一标记文本的帖子集</b>展开，按文本前缀从返回流中筛出再断言，避免对全局数据计数。
 */
class ContentFeedControllerEndpointTest extends ApiIntegrationTest {

    @Autowired
    private ContentPostRepository posts;

    @Autowired
    private PetProfileRepository petProfiles;

    private ContentPost savePost(long authorId, ContentType type, Long petId, String text) {
        return posts.save(ContentPost.publish(authorId, type, petId, text, List.of()));
    }

    /** 造一只属于 owner 的真实档案（GROWTH_MOMENT 帖的 pet_id 需满足 FK）。 */
    private long newPetProfile(long ownerId) {
        PetProfile p = PetProfile.create(ownerId, "宠物" + SEQ.incrementAndGet(), null, null, null, null,
                "ct-" + SEQ.incrementAndGet());
        return petProfiles.save(p).getId();
    }

    /** 把已持久化用户软删（set deleted_at）——FK 强约束下「注销作者」只能这样造，不能用悬空 author_id。 */
    private void softDelete(User u) {
        try {
            var f = User.class.getDeclaredField("deletedAt");
            f.setAccessible(true);
            f.set(u, Instant.now());
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
        users.save(u);
    }

    @Test
    void guestCanReadFeedReturns200() throws Exception {
        // 无 token 也放行（SecurityConfig GET 例外）。
        mvc.perform(get("/api/v1/content-posts"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isArray())
                .andExpect(jsonPath("$.hasMore").exists());
    }

    @Test
    void guestSeesNewlyPublishedPost() throws Exception {
        User author = newUser();
        String marker = "feedmark-" + SEQ.incrementAndGet();
        savePost(author.getId(), ContentType.DAILY, null, marker);

        // 倒序最新在前，首批 20 条内能取到刚发的。
        mvc.perform(get("/api/v1/content-posts"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[?(@.body=='" + marker + "')]").exists());
    }

    @Test
    void cursorPaginationDoesNotDuplicateOrSkip() throws Exception {
        User author = newUser();
        String marker = "page-" + SEQ.incrementAndGet() + "-";
        // 造 25 条带唯一前缀的帖，跨越 PAGE_SIZE(20) 边界。
        int total = FeedService.PAGE_SIZE + 5;
        for (int i = 0; i < total; i++) {
            savePost(author.getId(), ContentType.DAILY, null, marker + i);
        }

        List<String> collected = new ArrayList<>();
        String cursor = null;
        // 至多翻几页，把所有带 marker 的帖收齐（其它帖会混入但只挑 marker）。
        for (int guard = 0; guard < 10; guard++) {
            String url = "/api/v1/content-posts" + (cursor == null ? "" : "?cursor=" + cursor);
            MvcResult res = mvc.perform(get(url))
                    .andExpect(status().isOk())
                    .andReturn();
            var root = json.readTree(res.getResponse().getContentAsString());
            for (var item : root.get("items")) {
                String body = item.get("body").asText(null);
                if (body != null && body.startsWith(marker)) {
                    collected.add(body);
                }
            }
            boolean hasMore = root.get("hasMore").asBoolean();
            var next = root.get("nextCursor");
            if (!hasMore || next == null || next.isNull()) {
                break;
            }
            cursor = next.asText();
        }

        // 不漏：25 条全收到；不重：无重复 body。
        Assertions.assertThat(collected).hasSize(total);
        Assertions.assertThat(collected).doesNotHaveDuplicates();
    }

    @Test
    void categoryFilterReturnsOnlyMatchingType() throws Exception {
        User author = newUser();
        String know = "knowmark-" + SEQ.incrementAndGet();
        String daily = "dailymark-" + SEQ.incrementAndGet();
        savePost(author.getId(), ContentType.KNOWLEDGE, null, know);
        savePost(author.getId(), ContentType.DAILY, null, daily);

        MvcResult res = mvc.perform(get("/api/v1/content-posts?category=KNOWLEDGE"))
                .andExpect(status().isOk())
                .andReturn();
        var items = json.readTree(res.getResponse().getContentAsString()).get("items");
        // 该 Tab 返回的本测试帖只应是 KNOWLEDGE，绝不含 DAILY 标记。
        boolean sawKnow = false;
        for (var item : items) {
            String body = item.get("body").asText(null);
            if (daily.equals(body)) {
                Assertions.fail("KNOWLEDGE Tab 不应返回 DAILY 帖");
            }
            if (know.equals(body)) {
                sawKnow = true;
                Assertions.assertThat(item.get("type").asText()).isEqualTo("KNOWLEDGE");
            }
        }
        Assertions.assertThat(sawKnow).as("KNOWLEDGE Tab 应含本测试的 KNOWLEDGE 帖").isTrue();
    }

    @Test
    void petStatusBExcludesGrowthMoment() throws Exception {
        // B（计划养）用户：硬过滤掉成长日历快乐时刻。
        User viewerB = newUser(PetStatus.B);
        User poster = newUser(PetStatus.A);
        String growth = "growthmark-" + SEQ.incrementAndGet();
        String daily = "bdailymark-" + SEQ.incrementAndGet();
        // GROWTH_MOMENT 需 petId 非空且满足 FK → 造一只 poster 的真实档案。
        long petId = newPetProfile(poster.getId());
        savePost(poster.getId(), ContentType.GROWTH_MOMENT, petId, growth);
        savePost(poster.getId(), ContentType.DAILY, null, daily);

        MvcResult res = mvc.perform(get("/api/v1/content-posts")
                        .header("Authorization", userBearer(viewerB.getId())))
                .andExpect(status().isOk())
                .andReturn();
        var items = json.readTree(res.getResponse().getContentAsString()).get("items");
        boolean sawDaily = false;
        for (var item : items) {
            String body = item.get("body").asText(null);
            if (growth.equals(body)) {
                Assertions.fail("B 状态用户 Feed 不应含 GROWTH_MOMENT");
            }
            if (daily.equals(body)) {
                sawDaily = true;
            }
        }
        // DAILY 不受硬过滤，应可见（在首批 20 内）。
        Assertions.assertThat(sawDaily).as("B 状态仍应看到 DAILY 帖").isTrue();
    }

    @Test
    void deletedAuthorIsAnonymizedInFeed() throws Exception {
        // 注销作者：造真实用户 → 发帖 → 软删（deleted_at）。AccountQueryService 视 deleted_at 非空者为匿名。
        User author = newUser();
        long authorId = author.getId();
        String marker = "ghostmark-" + SEQ.incrementAndGet();
        savePost(authorId, ContentType.DAILY, null, marker);
        softDelete(author);

        MvcResult res = mvc.perform(get("/api/v1/content-posts"))
                .andExpect(status().isOk())
                .andReturn();
        var items = json.readTree(res.getResponse().getContentAsString()).get("items");
        boolean found = false;
        for (var item : items) {
            if (marker.equals(item.get("body").asText(null))) {
                found = true;
                Assertions.assertThat(item.get("authorDeleted").asBoolean()).isTrue();
                // 匿名化：昵称/头像不外泄（Jackson NON_NULL → 字段缺省或 null）。
                Assertions.assertThat(item.has("authorNickname")
                        && !item.get("authorNickname").isNull()).isFalse();
                Assertions.assertThat(item.get("authorId").asLong()).isEqualTo(authorId);
            }
        }
        Assertions.assertThat(found).as("应在首批 Feed 中看到注销作者的帖").isTrue();
    }
}
