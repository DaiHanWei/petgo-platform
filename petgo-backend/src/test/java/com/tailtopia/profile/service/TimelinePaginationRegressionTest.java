package com.tailtopia.profile.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.tailtopia.auth.domain.User;
import com.tailtopia.content.domain.ContentPost;
import com.tailtopia.content.domain.ContentType;
import com.tailtopia.content.repository.ContentPostRepository;
import com.tailtopia.profile.domain.PetProfile;
import com.tailtopia.profile.domain.PetType;
import com.tailtopia.profile.dto.TimelineItemResponse;
import com.tailtopia.profile.dto.TimelinePageResponse;
import com.tailtopia.profile.repository.PetProfileRepository;
import com.tailtopia.support.ApiIntegrationTest;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Story 3.1 · L1（需 Docker postgres）：<b>AC4 跨页无丢失无重复回归断言</b>，用**真库真 SQL**验证。
 *
 * <p>与 {@link TimelineCursorMergeTest}（L0，假数据源模拟 DB 语义）互补——L0 保证归并/截断逻辑正确，
 * 本类保证<b>新增的两条 JPQL 锚点查询在真实 PostgreSQL 上语义与之一致</b>。假数据源模型错了，L0 会绿而本类会红。
 *
 * <p><b>该断言常驻，不得删除或弱化</b>（AD-1 Rule 5 / NFR-1）。重构前的现网缺陷是：游标用
 * {@code created_at}、排序用 {@code event_date}，两把尺子——补记旧日期的日记跨页时丢失或重复。
 */
class TimelinePaginationRegressionTest extends ApiIntegrationTest {

    @Autowired
    private TimelineService timelineService;
    @Autowired
    private PetProfileRepository petProfiles;
    @Autowired
    private ContentPostRepository posts;

    /**
     * 核心回归：补记一篇上月的日记（{@code event_date} 旧、{@code created_at} 最新），
     * 逐页拉完后该条恰好出现一次、落在其事件日期对应的时序位置，且逐页集合 == 不分页集合。
     */
    @Test
    void ac4_backdatedEntryAppearsExactlyOnceAcrossPagesOnRealDb() {
        long uid = newOwnerWithPet();

        // 正常序列（插入顺序 = created_at 递增）
        savePost(uid, "2026-06-07", "p7");
        savePost(uid, "2026-06-08", "p8");
        savePost(uid, "2026-06-09", "p9");
        savePost(uid, "2026-06-10", "p10");
        // ⭐ 补记：事件日期是上月，但 created_at 是全场最新 —— 缺陷触发条件
        long backdatedId = savePost(uid, "2026-05-15", "backdated");

        List<TimelineItemResponse> unpaged = timelineService.getTimeline(uid, null, 50).items();
        assertThat(unpaged).hasSize(5);

        for (int pageSize : new int[] {1, 2, 3}) {
            List<TimelineItemResponse> paged = drain(uid, pageSize);

            assertThat(ids(paged))
                    .as("pageSize=%d：逐页结果必须与不分页完全一致（无丢失、无重复）", pageSize)
                    .isEqualTo(ids(unpaged));
            assertThat(new HashSet<>(ids(paged))).as("pageSize=%d：无重复", pageSize).hasSize(5);
            assertThat(paged.stream().filter(i -> backdatedId == i.postId()))
                    .as("pageSize=%d：补记条恰好出现一次", pageSize).hasSize(1);
            // 位置：事件日期最旧 → 排在最后
            assertThat(paged.get(paged.size() - 1).postId()).isEqualTo(backdatedId);
            assertThat(paged.get(paged.size() - 1).eventDate()).isEqualTo(LocalDate.parse("2026-05-15"));
        }
    }

    /** AC3：同日多条时，任何一天都不得被拆到两个页里（真库验证）。 */
    @Test
    void ac3_noDaySplitAcrossPagesOnRealDb() {
        long uid = newOwnerWithPet();
        savePost(uid, "2026-06-04", "d4");
        savePost(uid, "2026-06-05", "d5a");
        savePost(uid, "2026-06-05", "d5b");
        savePost(uid, "2026-06-05", "d5c");
        savePost(uid, "2026-06-06", "d6");

        for (int pageSize : new int[] {1, 2, 3}) {
            HashSet<LocalDate> seen = new HashSet<>();
            String cursor = null;
            for (int guard = 0; guard < 50; guard++) {
                TimelinePageResponse page = timelineService.getTimeline(uid, cursor, pageSize);
                HashSet<LocalDate> inPage = new HashSet<>();
                page.items().forEach(i -> inPage.add(i.effectiveDate()));
                for (LocalDate d : inPage) {
                    assertThat(seen).as("pageSize=%d：%s 被拆到了两个页里", pageSize, d).doesNotContain(d);
                }
                seen.addAll(inPage);
                if (!page.hasMore() || page.nextCursor() == null) {
                    break;
                }
                cursor = page.nextCursor();
            }
            assertThat(seen).hasSize(3);
        }
    }

    /**
     * 存量兜底（真库）：{@code event_date} 为 NULL 的历史行（V26 加列未回填）**不得从时间线消失**。
     *
     * <p>本用例专门覆盖新增的第二条 JPQL（NULL 分支）——只写第一条按 event_date 比较的查询时，
     * 这批行会被 SQL 直接过滤掉，用户看不到自己 V26 之前发的所有日记。
     */
    @Test
    void legacyNullEventDateRowsStillVisibleOnRealDb() {
        long uid = newOwnerWithPet();
        long legacyId = savePost(uid, null, "legacy-no-event-date"); // event_date IS NULL
        savePost(uid, "2026-06-20", "normal");

        List<TimelineItemResponse> all = drain(uid, 1);

        assertThat(ids(all)).as("存量 NULL event_date 行必须仍出现在时间线上").contains(legacyId);
        assertThat(all).hasSize(2);
    }

    // ===== helpers =====

    private long newOwnerWithPet() {
        User u = newUser();
        long uid = u.getId();
        petProfiles.save(PetProfile.create(uid, PetType.CAT, "Mochi", null, null, null, null,
                "TOK-" + SEQ.incrementAndGet()));
        return uid;
    }

    private long savePost(long uid, String eventDate, String text) {
        PetProfile pet = petProfiles.findByOwnerId(uid).orElseThrow();
        ContentPost p = posts.save(ContentPost.publish(uid, ContentType.GROWTH_MOMENT, pet.getId(),
                text, List.of("img/" + text + ".jpg"),
                eventDate == null ? null : LocalDate.parse(eventDate)));
        return p.getId();
    }

    private List<TimelineItemResponse> drain(long uid, int pageSize) {
        List<TimelineItemResponse> all = new ArrayList<>();
        String cursor = null;
        for (int guard = 0; guard < 50; guard++) {
            TimelinePageResponse page = timelineService.getTimeline(uid, cursor, pageSize);
            all.addAll(page.items());
            if (!page.hasMore() || page.nextCursor() == null) {
                return all;
            }
            cursor = page.nextCursor();
        }
        throw new IllegalStateException("翻页未收敛——游标未推进");
    }

    private static List<Long> ids(List<TimelineItemResponse> items) {
        return items.stream().map(TimelineItemResponse::postId).toList();
    }
}
