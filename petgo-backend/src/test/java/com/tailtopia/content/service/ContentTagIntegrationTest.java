package com.tailtopia.content.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tailtopia.auth.domain.User;
import com.tailtopia.content.domain.ContentPost;
import com.tailtopia.content.domain.ContentTag;
import com.tailtopia.content.domain.ContentType;
import com.tailtopia.content.domain.ContentVisibility;
import com.tailtopia.content.domain.DeleteReason;
import com.tailtopia.content.repository.ContentPostRepository;
import com.tailtopia.content.repository.ContentTagRepository;
import com.tailtopia.shared.error.AppException;
import com.tailtopia.support.ApiIntegrationTest;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * L1：内容装饰标签的打标校验、时间窗与三处下发（V1.1.6 Story 5.2）。
 *
 * <p>⚠️ 后台打标界面不在本轮，所以标签与打标都直接造。
 */
class ContentTagIntegrationTest extends ApiIntegrationTest {

    @Autowired
    private ContentTagRepository tags;

    @Autowired
    private ContentTagQueryService tagQuery;

    @Autowired
    private ContentPostRepository posts;

    @Autowired
    private ContentService contentService;

    private ContentTag newTag() {
        long n = SEQ.incrementAndGet();
        return tags.save(ContentTag.of("ct_" + n, "编辑推荐" + n, "🏆", "被官方选中的优质内容"));
    }

    private ContentPost publicPost(long authorId) {
        return posts.save(ContentPost.publish(authorId, ContentType.DAILY, null,
                "decorated-" + SEQ.incrementAndGet(), List.of(), null));
    }

    // ---------------------------------------------------------------- 仅公开内容可打标

    /**
     * 🛡 **仅公开内容可打标**（AC2）。
     *
     * <p>AC 的原话是"后台对未同步的私密 Diary 不展示打标入口"，但**后台入口本轮不做** ——
     * 只依赖"后台不展示"，这条在本轮等于没有实现。所以校验落在服务层，这条钉住它。
     */
    @Test
    void privateContentCannotBeDecorated() {
        User author = newUser();
        ContentPost post = publicPost(author.getId());
        post.setVisibility(ContentVisibility.PRIVATE);
        posts.save(post);
        ContentTag tag = newTag();

        assertThatThrownBy(() -> tagQuery.assign(post.getId(), tag.getId(),
                Instant.now().minusSeconds(60), null))
                .isInstanceOf(AppException.class);
    }

    /** 已下架的内容同样不可打标。 */
    @Test
    void removedContentCannotBeDecorated() {
        User author = newUser();
        ContentPost post = publicPost(author.getId());
        ContentTag tag = newTag();
        contentService.softDelete(post.getId(), DeleteReason.ADMIN_TAKEDOWN);

        assertThatThrownBy(() -> tagQuery.assign(post.getId(), tag.getId(),
                Instant.now().minusSeconds(60), null))
                .isInstanceOf(AppException.class);
    }

    @Test
    void publicContentCanBeDecorated() {
        User author = newUser();
        ContentPost post = publicPost(author.getId());
        ContentTag tag = newTag();

        tagQuery.assign(post.getId(), tag.getId(), Instant.now().minusSeconds(60), null);

        assertThat(tagQuery.findVisibleTags(List.of(post.getId()), Instant.now()))
                .containsKey(post.getId());
    }

    // ---------------------------------------------------------------- 时间窗

    /** 🛡 不设结束时间 = 永久（与用户标签同一份判定）。 */
    @Test
    void assignmentWithoutAnEndIsActiveForever() {
        User author = newUser();
        ContentPost post = publicPost(author.getId());
        tagQuery.assign(post.getId(), newTag().getId(), Instant.now().minusSeconds(60), null);

        assertThat(tagQuery.findVisibleTags(List.of(post.getId()),
                Instant.parse("2099-01-01T00:00:00Z"))).containsKey(post.getId());
    }

    /**
     * 过期后不再下发 —— 且 **×1.3 加权随之一并消失**。
     *
     * <p>加权由同一份查询时判定推导、没有状态列可漂移，所以"标签没了、加成还在"这种漂移
     * 在结构上就不可能发生。这条同时是那句 AC 的证据。
     */
    @Test
    void expiredAssignmentDropsTheTagAndThereforeTheWeight() {
        User author = newUser();
        ContentPost post = publicPost(author.getId());
        Instant start = Instant.parse("2026-10-01T03:00:00Z");
        Instant end = Instant.parse("2026-10-01T05:00:00Z");
        tagQuery.assign(post.getId(), newTag().getId(), start, end);

        assertThat(tagQuery.findVisibleTags(List.of(post.getId()), end.minusMillis(1)))
                .containsKey(post.getId());
        // 结束那一刻即失效（左闭右开）
        assertThat(tagQuery.findVisibleTags(List.of(post.getId()), end))
                .doesNotContainKey(post.getId());
    }

    /**
     * ⚠️ ×1.3 的**倍数口径**记录在代码里，但**本版本没有施加处** ——
     * 首页是纯时间倒序、没有排序算法。这条只钉住倍数没被人随手改掉。
     */
    @Test
    void rankWeightMultiplierIsRecordedEvenThoughNothingConsumesItYet() {
        assertThat(ContentTagQueryService.RANK_WEIGHT_MULTIPLIER).isEqualTo(1.3);
    }

    // ---------------------------------------------------------------- 三处下发

    /** 首页卡下发装饰标签。 */
    @Test
    void feedShipsDecorationTags() throws Exception {
        User author = newUser();
        ContentPost post = publicPost(author.getId());
        ContentTag tag = newTag();
        tagQuery.assign(post.getId(), tag.getId(), Instant.now().minusSeconds(60), null);

        String body = mvc.perform(get("/api/v1/content-posts"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        assertThat(body).contains("\"decorationTags\"").contains(tag.getCode());
    }

    /** 内容详情页下发装饰标签。 */
    @Test
    void detailShipsDecorationTags() throws Exception {
        User author = newUser();
        ContentPost post = publicPost(author.getId());
        ContentTag tag = newTag();
        tagQuery.assign(post.getId(), tag.getId(), Instant.now().minusSeconds(60), null);

        String body = mvc.perform(get("/api/v1/content-posts/" + post.getId()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        assertThat(body).contains("\"decorationTags\"").contains(tag.getCode());
    }

    /** 🛡 没有标签的内容**整个字段都不下发**（省掉每行一个空数组）。 */
    @Test
    void contentWithoutTagsOmitsTheFieldEntirely() throws Exception {
        User author = newUser();
        ContentPost post = publicPost(author.getId());

        String body = mvc.perform(get("/api/v1/content-posts/" + post.getId()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain("\"decorationTags\"");
    }

    /** 空集合直接短路，不发查询。 */
    @Test
    void emptyInputShortCircuits() {
        assertThat(tagQuery.findVisibleTags(List.of(), Instant.now())).isEmpty();
        assertThat(tagQuery.findVisibleTags(null, Instant.now())).isEmpty();
    }
}
