package com.tailtopia.content.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tailtopia.auth.domain.User;
import com.tailtopia.content.domain.ContentPin;
import com.tailtopia.content.domain.ContentPost;
import com.tailtopia.content.domain.ContentType;
import com.tailtopia.content.repository.ContentPinRepository;
import com.tailtopia.content.repository.ContentPostRepository;
import com.tailtopia.content.service.ContentPinService;
import com.tailtopia.social.service.UserHideRelationService;
import com.tailtopia.support.ApiIntegrationTest;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;

/**
 * L1 集成：顶置坑位套用拉黑过滤（Story 4.4 · FR-68 × FR-94）。
 *
 * <p>🔴 **这是补丁，补的是版本交叉重编号留下的缺口**：FR-68 正文只写了两条回退触发条件
 * （坑位为空、顶置期间内容被下架），**拉黑不在其中** —— 只读 FR-68 不会知道要加这层。
 *
 * <p>而隐藏关系那个只读端口的注释里其实早就写明了：
 * 「若某个**运营干预位（顶置位**、推荐位等）命中被隐藏作者，则对该用户**视为该位为空**，
 * 按该功能自身已定义的『位为空』回退逻辑处理…**漏一处等于拉黑白拉**」。
 * 顶置位正是它列的六处生效点之一，只是当时因版本分线没接上。
 *
 * <p>🛡 三条不得弱化：拉黑者看到空位、**其他人不受影响**、举报隐藏同样生效。
 */
class PinnedSlotHideFilterIntegrationTest extends ApiIntegrationTest {

    @Autowired
    private ContentPostRepository posts;

    @Autowired
    private ContentPinRepository pins;

    @Autowired
    private ContentPinService pinService;

    @Autowired
    private UserHideRelationService hideRelations;

    /** ⚠️ 用真实坑位且 L1 不回滚：上一个用例的排期会撞"时间窗不可重叠"。 */
    @BeforeEach
    void clearHomeFeedPins() {
        pins.deleteAll(pins.findAll().stream()
                .filter(p -> ContentPin.SLOT_HOME_FEED.equals(p.getSlot()))
                .toList());
    }

    private ContentPost savePost(long authorId, String text) {
        return posts.save(ContentPost.publish(authorId, ContentType.DAILY, null, text, List.of()));
    }

    private void pinNow(long contentId) {
        Instant now = Instant.now();
        pinService.schedule(ContentPin.ofContent(ContentPin.SLOT_HOME_FEED, contentId,
                now.minus(1, ChronoUnit.HOURS), now.plus(1, ChronoUnit.HOURS)));
    }

    private void pinPromoNow() {
        Instant now = Instant.now();
        pinService.schedule(ContentPin.ofPromo(ContentPin.SLOT_HOME_FEED,
                "https://cdn.example/promo.jpg", "夏日专场", null,
                now.minus(1, ChronoUnit.HOURS), now.plus(1, ChronoUnit.HOURS)));
    }

    // ——————————————————— 🛡 AC1 拉黑者看到空位，其他人不受影响 ———————————————————

    @Test
    void blockedAuthorMakesTheSlotEmptyForThatViewerOnly() throws Exception {
        User author = newUser();
        User blocker = newUser();
        User bystander = newUser();
        ContentPost post = savePost(author.getId(), "被顶置的内容");
        pinNow(post.getId());

        // 拉黑之前：两个人都看得到顶置
        mvc.perform(get("/api/v1/content-posts/pinned")
                        .header(HttpHeaders.AUTHORIZATION, userBearer(blocker.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pin").exists());

        hideRelations.block(blocker.getId(), author.getId());

        // 拉黑者：坑位为空
        mvc.perform(get("/api/v1/content-posts/pinned")
                        .header(HttpHeaders.AUTHORIZATION, userBearer(blocker.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pin").doesNotExist());

        // 🛡 旁人照常看到 —— 顶置是运营的编排结果，不能因为某个人拉黑就对所有人消失
        mvc.perform(get("/api/v1/content-posts/pinned")
                        .header(HttpHeaders.AUTHORIZATION, userBearer(bystander.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pin").exists());
    }

    /** ✅ `user_hide_relations` 不区分 source ⇒ 举报隐藏**一次覆盖**，无需分别处理。 */
    @Test
    void reportHiddenAuthorAlsoEmptiesTheSlot() throws Exception {
        User author = newUser();
        User reporter = newUser();
        ContentPost post = savePost(author.getId(), "举报隐藏也该生效");
        pinNow(post.getId());

        hideRelations.hideByReport(reporter.getId(), author.getId());

        mvc.perform(get("/api/v1/content-posts/pinned")
                        .header(HttpHeaders.AUTHORIZATION, userBearer(reporter.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pin").doesNotExist());
    }

    /** 游客没有拉黑关系 —— 整批短路，照常看到顶置。 */
    @Test
    void guestsAreUnaffected() throws Exception {
        User author = newUser();
        User blocker = newUser();
        ContentPost post = savePost(author.getId(), "游客照常可见");
        pinNow(post.getId());
        hideRelations.block(blocker.getId(), author.getId());

        mvc.perform(get("/api/v1/content-posts/pinned"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pin").exists());
    }

    /** 🛡 推广卡片不对应任何作者 —— 不参与本过滤。 */
    @Test
    void promoCardsAreNotFilteredByHideRelations() throws Exception {
        User author = newUser();
        User blocker = newUser();
        hideRelations.block(blocker.getId(), author.getId());
        pinPromoNow();

        mvc.perform(get("/api/v1/content-posts/pinned")
                        .header(HttpHeaders.AUTHORIZATION, userBearer(blocker.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pin.pinType").value("PROMO"));
    }

    // ——————————————————— AC1 后半：普通内容照常填充 ———————————————————

    /**
     * 🛡 坑位对拉黑者为空之后，**Feed 首屏照常由普通内容填充** ——
     * 不为该用户单独选替补顶置（那会让"运营配了什么"变得不可预期、也无法解释）。
     *
     * <p>顺带钉住一件事：**首屏"让位"那处不需要再套一遍过滤**。
     * 被拉黑作者的内容本来就被 `findFeed` 的账号维度子查询排除了，
     * 让位用的 `excludeId` 落在一条已经被排除的内容上是 no-op ——
     * 在那里再查一次隐藏关系，只是每次首屏多一次 DB 往返，行为一模一样。
     */
    @Test
    void feedFirstPageStillFilledWithOrdinaryContentForTheBlocker() throws Exception {
        User author = newUser();
        User blocker = newUser();
        // ⚠️ 正文必须**每次唯一**：库是共享的、不回滚，写死的正文会命中上一轮留下的同名内容
        //    （作者是另一个人、未被拉黑，于是它合理地出现在 Feed 里，看着像过滤失效）。
        //    本条第一版就是这么误判的 —— 与"用固定标签码撞唯一约束"同一类毛病。
        String marker = "顶置的那条-" + SEQ.incrementAndGet();
        ContentPost pinned = savePost(author.getId(), marker);
        pinNow(pinned.getId());
        // 另造几条别人的内容，确保首屏有东西可填
        User other = newUser();
        for (int i = 0; i < 3; i++) {
            savePost(other.getId(), "普通内容-" + SEQ.incrementAndGet());
        }
        hideRelations.block(blocker.getId(), author.getId());

        String body = mvc.perform(get("/api/v1/content-posts")
                        .header(HttpHeaders.AUTHORIZATION, userBearer(blocker.getId())))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        var root = json.readTree(body);

        assertThat(root.get("items").size()).as("首屏应照常有普通内容").isGreaterThan(0);
        // 🛡 被拉黑作者的内容不该出现在 Feed 里（这是 V1.1.4 那层的既有行为，这里一并确认）
        for (var item : root.get("items")) {
            var bodyNode = item.get("body");
            String text = (bodyNode == null || bodyNode.isNull()) ? null : bodyNode.asText();
            assertThat(text).isNotEqualTo(marker);
        }
    }
}
