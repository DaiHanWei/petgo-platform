package com.tailtopia.content.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tailtopia.auth.domain.User;
import com.tailtopia.content.domain.ContentPin;
import com.tailtopia.content.domain.ContentPost;
import com.tailtopia.content.domain.ContentType;
import com.tailtopia.content.domain.DeleteReason;
import com.tailtopia.content.repository.ContentPostRepository;
import com.tailtopia.content.service.ContentPinService;
import com.tailtopia.content.service.ContentService;
import com.tailtopia.support.ApiIntegrationTest;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * L1：顶置坑位取数 + 只首屏让位（V1.1.6 Story 4.2）。
 *
 * <p>⚠️ 后台配置界面不在本轮，所以排期直接造。
 */
class PinnedSlotIntegrationTest extends ApiIntegrationTest {

    @Autowired
    private ContentPostRepository posts;

    @Autowired
    private ContentPinService pinService;

    @Autowired
    private ContentService contentService;

    @Autowired
    private com.tailtopia.content.repository.ContentPinRepository pins;

    /**
     * ⚠️ 本类用的是**真实坑位** {@code HOME_FEED}，而 L1 不回滚 ——
     * 上一个用例留下的排期会让下一个用例撞上"时间窗不可重叠"的校验。
     * 每个用例开跑前清掉该坑位的历史排期（本轮还没有后台配置界面，这些行必然都是测试造的）。
     */
    @org.junit.jupiter.api.BeforeEach
    void clearHomeFeedPins() {
        pins.deleteAll(pins.findAll().stream()
                .filter(p -> ContentPin.SLOT_HOME_FEED.equals(p.getSlot()))
                .toList());
    }

    private ContentPost savePost(long authorId, String text) {
        return posts.save(ContentPost.publish(authorId, ContentType.DAILY, null, text, List.of(), null));
    }

    /** 造一条**现在就生效**的顶置。 */
    private ContentPin pinNow(long contentId) {
        Instant now = Instant.now();
        return pinService.schedule(ContentPin.ofContent(ContentPin.SLOT_HOME_FEED, contentId,
                now.minusSeconds(60), now.plusSeconds(3600)));
    }

    private String feed(String cursor) throws Exception {
        var req = get("/api/v1/content-posts");
        if (cursor != null) {
            req = req.param("cursor", cursor);
        }
        return mvc.perform(req).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    private String pinnedSlot() throws Exception {
        return mvc.perform(get("/api/v1/content-posts/pinned"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    // ---------------------------------------------------------------- 坑位取数

    /** 🛡 顶置条目下发的就是**与普通条目同一个 DTO** —— 客户端才能用同一个卡片组件渲染。 */
    @Test
    void pinnedSlotShipsTheSameShapeAsAnOrdinaryFeedItem() throws Exception {
        User author = newUser();
        ContentPost post = savePost(author.getId(), "pinned body");
        ContentPin pin = pinNow(post.getId());

        String body = pinnedSlot();

        assertThat(body).contains("\"pinConfigId\":" + pin.getId());
        assertThat(body).contains("\"pinType\":\"CONTENT\"");
        // 同构：普通条目有的这些字段，顶置条目一个不少
        assertThat(body).contains("\"id\":" + post.getId());
        assertThat(body).contains("\"body\":\"pinned body\"");
        assertThat(body).contains("\"likeCount\"").contains("\"commentCount\"").contains("\"liked\"");
    }

    /** 🛡 无生效配置 → 坑位为空，**不是错误**（客户端什么都不渲染、不留占位）。 */
    @Test
    void emptySlotIsNotAnError() throws Exception {
        String body = pinnedSlot();
        assertThat(body).doesNotContain("\"pinConfigId\"");
    }

    /**
     * 顶置内容已不可展示 → 视为坑位为空。
     *
     * <p>Story 4.1 的下架联动会即时结束排期；这里再兜一道，防事件与查询之间的窗口。
     */
    @Test
    void takenDownContentYieldsAnEmptySlot() throws Exception {
        ContentPost post = savePost(newUser().getId(), "will be removed");
        pinNow(post.getId());
        assertThat(pinnedSlot()).contains("\"pinConfigId\"");

        contentService.softDelete(post.getId(), DeleteReason.ADMIN_TAKEDOWN);

        assertThat(pinnedSlot()).doesNotContain("\"pinConfigId\"");
    }

    // ---------------------------------------------------------------- 只首屏让位

    /**
     * 🛡 **只从第一页排除，后续页仍可出现**。
     *
     * <p>做法上把排除写在查询条件里而不是取完再内存剔除 —— 后者会让第一页少一条、
     * 且"还有更多"的判断（依赖多取一条）跟着失真。
     */
    @Test
    void pinnedContentYieldsOnFirstPageOnly() throws Exception {
        User author = newUser();
        // 造满两页多一点，保证被顶置那条会落到后续页
        ContentPost pinned = savePost(author.getId(), "PINNED-" + SEQ.incrementAndGet());
        for (int i = 0; i < 45; i++) {
            savePost(author.getId(), "filler-" + i);
        }
        pinNow(pinned.getId());

        String first = feed(null);
        assertThat(first).doesNotContain("\"id\":" + pinned.getId() + ",");

        // 一路翻到底，被顶置那条**必须**在某一后续页出现
        String cursor = extractCursor(first);
        boolean seenLater = false;
        int guard = 0;
        while (cursor != null && guard++ < 20) {
            String page = feed(cursor);
            if (page.contains("\"id\":" + pinned.getId() + ",")) {
                seenLater = true;
                break;
            }
            cursor = extractCursor(page);
        }
        assertThat(seenLater)
                .as("被顶置的内容只从第一页排除，后续页仍应正常出现（AD-8）")
                .isTrue();
    }

    /** 🛡 排除之后第一页仍是**满页** —— 页大小抖动会影响"还有更多"的判断。 */
    @Test
    void firstPageStaysFullAfterYielding() throws Exception {
        User author = newUser();
        ContentPost pinned = savePost(author.getId(), "PINNED-FULL-" + SEQ.incrementAndGet());
        for (int i = 0; i < 30; i++) {
            savePost(author.getId(), "filler-full-" + i);
        }
        pinNow(pinned.getId());

        int withPin = countItems(feed(null));
        assertThat(withPin).isEqualTo(20);
    }

    /** 无生效顶置 → 一条都不排除（不能因为"顺手"把最新那条也挡掉）。 */
    @Test
    void withoutAnActivePinNothingIsExcluded() throws Exception {
        User author = newUser();
        ContentPost newest = savePost(author.getId(), "NEWEST-" + SEQ.incrementAndGet());

        assertThat(feed(null)).contains("\"id\":" + newest.getId() + ",");
    }

    /**
     * 排期尚未开始 / 已经结束 → 不让位（让位判定与坑位判定是同一份，不该各算各的）。
     */
    @Test
    void pendingScheduleDoesNotYield() throws Exception {
        User author = newUser();
        ContentPost future = savePost(author.getId(), "FUTURE-" + SEQ.incrementAndGet());
        Instant now = Instant.now();
        pinService.schedule(ContentPin.ofContent(ContentPin.SLOT_HOME_FEED, future.getId(),
                now.plusSeconds(3600), now.plusSeconds(7200)));

        assertThat(feed(null)).contains("\"id\":" + future.getId() + ",");
    }


    // ---------------------------------------------------------------- 推广卡片（Story 4.3）

    /** 推广卡片下发三个字段，且 **item 为空**（它不对应任何真实帖子）。 */
    @Test
    void promoCardShipsItsThreeFields() throws Exception {
        Instant now = Instant.now();
        pinService.schedule(ContentPin.ofPromo(ContentPin.SLOT_HOME_FEED,
                "https://cdn.example.com/banner.jpg", "Ikut lomba foto anabul!",
                "tailtopia://open", now.minusSeconds(60), now.plusSeconds(3600)));

        String body = pinnedSlot();

        assertThat(body).contains("\"pinType\":\"PROMO\"");
        assertThat(body).contains("\"imageUrl\":\"https://cdn.example.com/banner.jpg\"");
        assertThat(body).contains("\"title\":\"Ikut lomba foto anabul!\"");
        assertThat(body).contains("\"linkUrl\":\"tailtopia://open\"");
        assertThat(body).doesNotContain("\"item\"");
    }

    /** 跳转目标可空 = 纯展示卡。 */
    @Test
    void promoCardWithoutALinkIsStillValid() throws Exception {
        Instant now = Instant.now();
        pinService.schedule(ContentPin.ofPromo(ContentPin.SLOT_HOME_FEED,
                "https://cdn.example.com/b.jpg", "Judul", null,
                now.minusSeconds(60), now.plusSeconds(3600)));

        assertThat(pinnedSlot()).contains("\"pinType\":\"PROMO\"");
    }

    /** 图片或标题缺失 → 拦截（给运营一句人话，而不是抛一串英文约束名）。 */
    @Test
    void promoCardRequiresImageAndTitle() {
        Instant now = Instant.now();
        assertThatThrownBy(() -> pinService.schedule(ContentPin.ofPromo(
                ContentPin.SLOT_HOME_FEED, "", "Judul", null,
                now.minusSeconds(60), now.plusSeconds(3600))))
                .isInstanceOf(com.tailtopia.shared.error.AppException.class);

        assertThatThrownBy(() -> pinService.schedule(ContentPin.ofPromo(
                ContentPin.SLOT_HOME_FEED, "https://cdn.example.com/b.jpg", null, null,
                now.minusSeconds(60), now.plusSeconds(3600))))
                .isInstanceOf(com.tailtopia.shared.error.AppException.class);
    }

    /** 🛡 推广卡片**无内容编号**，天然不参与"只首屏让位" —— 首页一条都不该被挡掉。 */
    @Test
    void promoCardNeverExcludesAnythingFromTheFeed() throws Exception {
        User author = newUser();
        ContentPost newest = savePost(author.getId(), "NEWEST-PROMO-" + SEQ.incrementAndGet());
        Instant now = Instant.now();
        pinService.schedule(ContentPin.ofPromo(ContentPin.SLOT_HOME_FEED,
                "https://cdn.example.com/b.jpg", "Judul", null,
                now.minusSeconds(60), now.plusSeconds(3600)));

        assertThat(feed(null)).contains("\"id\":" + newest.getId() + ",");
    }

    private static String extractCursor(String body) {
        int i = body.indexOf("\"nextCursor\":\"");
        if (i < 0) {
            return null;
        }
        int start = i + "\"nextCursor\":\"".length();
        int end = body.indexOf('"', start);
        return body.substring(start, end);
    }

    private static int countItems(String body) {
        int count = 0;
        int i = 0;
        while ((i = body.indexOf("\"createdAt\":", i)) >= 0) {
            count++;
            i += 12;
        }
        return count;
    }
}
