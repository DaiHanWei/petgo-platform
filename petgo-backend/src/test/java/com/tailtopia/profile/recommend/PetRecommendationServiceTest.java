package com.tailtopia.profile.recommend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tailtopia.auth.service.AccountQueryService;
import com.tailtopia.content.service.ContentService;
import com.tailtopia.profile.domain.PetProfile;
import com.tailtopia.profile.domain.PetType;
import com.tailtopia.profile.repository.PetProfileRepository;
import com.tailtopia.social.read.UserHideRelationReader;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * L0：推荐池的过滤与组装（V1.3.0 batch-b1 Story 4.1 · AC1/AC2/AC3/AC4）。
 *
 * <h2>这里能验什么、不能验什么</h2>
 * <ul>
 *   <li>✅ **过滤与组装**：AC3 那四条排除、AC4 的两个图片字段、陪伴天数、批量取数形态；</li>
 *   <li>✅ **禁用项**（AC2 的 L0 那一半）：不许有缓存 / 调度中间件；</li>
 *   <li>❌ **排序与索引**：排序在 SQL 的 {@code ORDER BY} 里、索引要看 {@code EXPLAIN} ——
 *       两者都只有真库能验（L1）。这里能做的是钉住「本类<b>不重排</b>」——
 *       在内存里再 sort 一次就等于悄悄换掉了 AC1 的排序口径。</li>
 * </ul>
 */
class PetRecommendationServiceTest {

    private static final long VIEWER = 1L;
    private static final Instant NOW = Instant.parse("2026-09-15T12:00:00Z");

    private ContentService content;
    private PetProfileRepository profiles;
    private AccountQueryService accounts;
    private UserHideRelationReader hideRelations;
    private PetRecommendationService service;

    @BeforeEach
    void setUp() {
        content = mock(ContentService.class);
        profiles = mock(PetProfileRepository.class);
        accounts = mock(AccountQueryService.class);
        hideRelations = mock(UserHideRelationReader.class);
        service = new PetRecommendationService(content, profiles, accounts, hideRelations);
        when(hideRelations.hiddenEitherWay(anyLong(), anyCollection())).thenReturn(Set.of());
        when(content.findLatestPublicCovers(anyCollection())).thenReturn(Map.of());
        // 默认：这批 owner 全部有效（未注销、未封号）。
        when(accounts.activeIdsAmong(anyCollection())).thenAnswer(
                inv -> new java.util.HashSet<>(inv.<java.util.Collection<Long>>getArgument(0)));
    }

    /** 造一只满足入池门槛的宠物（有头像、owner 未注销、未拉黑）。 */
    private PetProfile pet(long petId, long ownerId, String avatarUrl, Instant createdAt) {
        PetProfile p = PetProfile.create(ownerId, PetType.CAT, "P" + petId, avatarUrl,
                null, LocalDate.of(2024, 1, 1), null, "tok" + petId);
        setField(p, "id", petId);
        setField(p, "createdAt", createdAt);
        return p;
    }

    private static void setField(PetProfile p, String name, Object value) {
        try {
            var f = PetProfile.class.getDeclaredField(name);
            f.setAccessible(true);
            f.set(p, value);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    /** content 侧候选（已按 AC1 排好序）。 */
    private void candidates(List<Long> petIds) {
        List<ContentService.RecommendablePet> rows = new ArrayList<>();
        long offset = 0;
        for (Long id : petIds) {
            rows.add(new ContentService.RecommendablePet(
                    id, NOW.minusSeconds(++offset * 60), 5L, 10L));
        }
        when(content.findRecommendablePets(org.mockito.ArgumentMatchers.any(), anyInt(), anyInt(),
                org.mockito.ArgumentMatchers.any())).thenReturn(rows);
    }

    private void withProfiles(PetProfile... pets) {
        when(profiles.findAllById(anyCollection())).thenReturn(List.of(pets));
    }

    // ===== AC1/AC2：排序口径由 SQL 定，本类不重排 =====

    @Test
    void 保持content侧给的顺序不在内存里重排() {
        // 🔴 AC1 的排序（最近更新倒序，同日按互动量）在 SQL 的 ORDER BY 里。
        //    这里再 sort 一次就等于悄悄换掉了那个口径 —— 而两处排序迟早分叉。
        candidates(List.of(30L, 10L, 20L));
        withProfiles(pet(10L, 110L, "a10", NOW), pet(20L, 120L, "a20", NOW),
                pet(30L, 130L, "a30", NOW));

        assertThat(service.recommendFor(VIEWER, 10, NOW))
                .extracting(RecommendedPetResponse::petId)
                .containsExactly(30L, 10L, 20L);
    }

    @Test
    void 候选窗口是14天门槛是3条() {
        candidates(List.of(10L));
        withProfiles(pet(10L, 110L, "a10", NOW));
        service.recommendFor(VIEWER, 10, NOW);

        ArgumentCaptor<Instant> since = ArgumentCaptor.forClass(Instant.class);
        ArgumentCaptor<Integer> minRecords = ArgumentCaptor.forClass(Integer.class);
        verify(content).findRecommendablePets(since.capture(), minRecords.capture(), anyInt(),
                org.mockito.ArgumentMatchers.any());
        assertThat(ChronoUnit.DAYS.between(since.getValue(), NOW)).isEqualTo(14);
        assertThat(minRecords.getValue()).isEqualTo(3);
    }

    @Test
    void 多取候选留给后面三层过滤() {
        // 🔴 「有头像 / owner 未注销 / 互相拉黑」三条都发生在 SQL 的 LIMIT **之后**。
        //    取多少就展示多少的话，一页会越过滤越空。
        candidates(List.of(10L));
        withProfiles(pet(10L, 110L, "a10", NOW));
        service.recommendFor(VIEWER, 10, NOW);

        ArgumentCaptor<Integer> limit = ArgumentCaptor.forClass(Integer.class);
        verify(content).findRecommendablePets(org.mockito.ArgumentMatchers.any(), anyInt(),
                limit.capture(), org.mockito.ArgumentMatchers.any());
        assertThat(limit.getValue()).isGreaterThan(10);
    }

    @Test
    void 取数恰好一条聚合加三次批量与候选数无关() {
        candidates(List.of(10L, 20L, 30L, 40L, 50L));
        withProfiles(pet(10L, 110L, "a", NOW), pet(20L, 120L, "a", NOW),
                pet(30L, 130L, "a", NOW), pet(40L, 140L, "a", NOW), pet(50L, 150L, "a", NOW));

        service.recommendFor(VIEWER, 10, NOW);

        verify(content, times(1)).findRecommendablePets(org.mockito.ArgumentMatchers.any(),
                anyInt(), anyInt(), org.mockito.ArgumentMatchers.any());
        verify(profiles, times(1)).findAllById(anyCollection());
        verify(accounts, times(1)).activeIdsAmong(anyCollection());
        verify(hideRelations, times(1)).hiddenEitherWay(anyLong(), anyCollection());
        verify(content, times(1)).findLatestPublicCovers(anyCollection());
    }

    @Test
    void 候选为空时后面一条查询都不发() {
        candidates(List.of());
        assertThat(service.recommendFor(VIEWER, 10, NOW)).isEmpty();
        verify(profiles, never()).findAllById(anyCollection());
        verify(hideRelations, never()).hiddenEitherWay(anyLong(), anyCollection());
    }

    // ===== AC3：推荐池边界 =====

    @Test
    void 没有头像的宠物不入池() {
        // AC1 的入池门槛之一。没头像的卡左下角是个空圈，不如不推。
        candidates(List.of(10L, 20L));
        withProfiles(pet(10L, 110L, null, NOW), pet(20L, 120L, "a20", NOW));
        assertThat(service.recommendFor(VIEWER, 10, NOW))
                .extracting(RecommendedPetResponse::petId).containsExactly(20L);
    }

    @Test
    void 头像是空串也算没有头像() {
        candidates(List.of(10L));
        withProfiles(pet(10L, 110L, "  ", NOW));
        assertThat(service.recommendFor(VIEWER, 10, NOW)).isEmpty();
    }

    @Test
    void 已注销用户的宠物不入池() {
        candidates(List.of(10L, 20L));
        withProfiles(pet(10L, 110L, "a10", NOW), pet(20L, 120L, "a20", NOW));
        // 110 注销 → activeIdsAmong 不回它（判据在 AccountQueryService 里，与落地页同一套）。
        when(accounts.activeIdsAmong(anyCollection())).thenReturn(Set.of(120L));
        assertThat(service.recommendFor(VIEWER, 10, NOW))
                .extracting(RecommendedPetResponse::petId).containsExactly(20L);
    }

    @Test
    void 被封号用户的宠物也不入池() {
        // 🔴 code-review 2026-09-15：此前只判「注销」（AuthorView.deleted），
        //    于是**被封号**（status=DEACTIVATED）用户的宠物照样入池 ——
        //    而落地页 findVisibleProfileById 用的是 isActive（注销 + 封号都不可见），
        //    表现是「推荐位里那只宠物点进去 404」。
        //    改用 activeIdsAmong 后两处判据是同一份。
        candidates(List.of(10L, 20L));
        withProfiles(pet(10L, 110L, "a10", NOW), pet(20L, 120L, "a20", NOW));
        when(accounts.activeIdsAmong(anyCollection())).thenReturn(Set.of(110L));
        assertThat(service.recommendFor(VIEWER, 10, NOW))
                .extracting(RecommendedPetResponse::petId).containsExactly(10L);
    }

    @Test
    void owner有效性判定走的是批量方法且判据含封号() {
        // 机械钉住「用的是 activeIdsAmong 而不是 AuthorView.deleted」：
        // 后者只看软删，读不到 status —— 换回去本条即红。
        candidates(List.of(10L));
        withProfiles(pet(10L, 110L, "a10", NOW));
        service.recommendFor(VIEWER, 10, NOW);
        verify(accounts, times(1)).activeIdsAmong(anyCollection());
        verify(accounts, never()).findAuthorViewsWithoutTags(anyCollection());

        // 而 activeIdsAmong 的判据本身（未软删 + status=ACTIVE）钉在仓储查询文本上。
        String q = readSource("src/main/java/com/tailtopia/auth/repository/UserRepository.java");
        int at = q.indexOf("findActiveIds");
        assertThat(at).isGreaterThan(0);
        String around = q.substring(Math.max(0, at - 400), at);
        assertThat(around).contains("deletedAt IS NULL").contains("UserStatus.ACTIVE");
    }

    @Test
    void 任一方向有拉黑关系就不互推() {
        // 🔴 双向：只判单向的表现是「我拉黑了他，他家的宠物还在我的推荐位里」。
        candidates(List.of(10L, 20L));
        withProfiles(pet(10L, 110L, "a10", NOW), pet(20L, 120L, "a20", NOW));
        when(hideRelations.hiddenEitherWay(anyLong(), anyCollection())).thenReturn(Set.of(110L));
        assertThat(service.recommendFor(VIEWER, 10, NOW))
                .extracting(RecommendedPetResponse::petId).containsExactly(20L);
    }

    @Test
    void 拉黑判定走的是统一读取口的批量双向方法() {
        candidates(List.of(10L));
        withProfiles(pet(10L, 110L, "a10", NOW));
        service.recommendFor(VIEWER, 10, NOW);
        verify(hideRelations).hiddenEitherWay(org.mockito.ArgumentMatchers.eq(VIEWER),
                anyCollection());
        verify(hideRelations, never()).isHidden(anyLong(), anyLong());
    }

    @Test
    void 自己的宠物不出现在逛别人家的里() {
        candidates(List.of(10L, 20L));
        withProfiles(pet(10L, VIEWER, "a10", NOW), pet(20L, 120L, "a20", NOW));
        assertThat(service.recommendFor(VIEWER, 10, NOW))
                .extracting(RecommendedPetResponse::petId).containsExactly(20L);
    }

    @Test
    void 档案已删但内容还在的宠物被跳过而不是抛() {
        candidates(List.of(10L, 20L));
        withProfiles(pet(20L, 120L, "a20", NOW)); // 10 号档案查不到
        assertThat(service.recommendFor(VIEWER, 10, NOW))
                .extracting(RecommendedPetResponse::petId).containsExactly(20L);
    }

    @Test
    void 过滤之后不足一页也照样返回不补齐() {
        // 不补齐是刻意的：补齐意味着把排序更靠后的人提上来，而那已经不是 AC1 的口径了。
        candidates(List.of(10L, 20L));
        withProfiles(pet(10L, 110L, null, NOW), pet(20L, 120L, "a20", NOW));
        assertThat(service.recommendFor(VIEWER, 10, NOW)).hasSize(1);
    }

    @Test
    void limit生效且不会被客户端撑爆() {
        candidates(List.of(10L, 20L, 30L));
        withProfiles(pet(10L, 110L, "a", NOW), pet(20L, 120L, "a", NOW), pet(30L, 130L, "a", NOW));
        assertThat(service.recommendFor(VIEWER, 2, NOW)).hasSize(2);

        // 一个巨大的 limit 不该让它去捞一整张表。
        service.recommendFor(VIEWER, 100000, NOW);
        ArgumentCaptor<Integer> poolSize = ArgumentCaptor.forClass(Integer.class);
        verify(content, org.mockito.Mockito.atLeastOnce())
                .findRecommendablePets(org.mockito.ArgumentMatchers.any(), anyInt(),
                        poolSize.capture(), org.mockito.ArgumentMatchers.any());
        assertThat(poolSize.getAllValues()).allSatisfy(v -> assertThat(v).isLessThan(1000));
    }

    // ===== AC4：卡片字段 =====

    @Test
    void 大图与小圆头像是两个不同来源的字段() {
        // 🔴 UI 稿 UX-DR15 专门点过：做成同一张图重复摆放是明显 bug。
        candidates(List.of(10L));
        withProfiles(pet(10L, 110L, "https://cdn/avatar.jpg", NOW));
        when(content.findLatestPublicCovers(anyCollection()))
                .thenReturn(Map.of(10L, "https://cdn/latest-post.jpg"));

        RecommendedPetResponse card = service.recommendFor(VIEWER, 10, NOW).getFirst();
        assertThat(card.avatarUrl()).startsWith("https://cdn/avatar.jpg?");
        assertThat(card.coverImageUrl()).startsWith("https://cdn/latest-post.jpg?");
        assertThat(card.coverImageUrl()).isNotEqualTo(card.avatarUrl());
    }

    /**
     * 🔒 batch-b1 复审 B3：他人宠物的头像与帖子配图对外分发一律服务端去 EXIF
     * （与他人主页 / 访客视图同口径），防改过的客户端绕过客户端剥离泄漏 GPS。
     * 用「缩放 + 去 EXIF」一体串 —— 客户端遇到已带 x-oss-process 的 URL 不再追加缩略图参数。
     */
    @Test
    void 两张图都经服务端去EXIF且带缩放() {
        candidates(List.of(10L));
        withProfiles(pet(10L, 110L, "https://cdn/avatar.jpg", NOW));
        when(content.findLatestPublicCovers(anyCollection()))
                .thenReturn(Map.of(10L, "https://cdn/latest-post.jpg"));

        RecommendedPetResponse card = service.recommendFor(VIEWER, 10, NOW).getFirst();
        assertThat(card.avatarUrl()).isEqualTo(com.tailtopia.shared.media.AliyunOssClient
                .exifStrippedThumbUrl("https://cdn/avatar.jpg",
                        PetRecommendationService.AVATAR_THUMB_WIDTH_PX));
        assertThat(card.coverImageUrl()).isEqualTo(com.tailtopia.shared.media.AliyunOssClient
                .exifStrippedThumbUrl("https://cdn/latest-post.jpg",
                        PetRecommendationService.COVER_THUMB_WIDTH_PX));
        assertThat(card.avatarUrl()).contains("x-oss-process=image/resize,w_").contains("/format,jpg");
        assertThat(card.coverImageUrl()).contains("x-oss-process=image/resize,w_").contains("/format,jpg");
    }

    @Test
    void 没有带图公开帖时封面为空但仍然入池() {
        // AC1 的门槛里**没有**「必须有配图」这一条 —— 3 条公开记录全是纯文字的宠物照样推。
        candidates(List.of(10L));
        withProfiles(pet(10L, 110L, "a10", NOW));
        when(content.findLatestPublicCovers(anyCollection())).thenReturn(Map.of());

        RecommendedPetResponse card = service.recommendFor(VIEWER, 10, NOW).getFirst();
        assertThat(card.coverImageUrl()).isNull();
        assertThat(card.avatarUrl()).startsWith("a10?");
    }

    @Test
    void 封面只为通过过滤的宠物查() {
        // 被过滤掉的宠物不该白查一次封面。
        candidates(List.of(10L, 20L));
        withProfiles(pet(10L, 110L, null, NOW), pet(20L, 120L, "a20", NOW));
        service.recommendFor(VIEWER, 10, NOW);
        ArgumentCaptor<java.util.Collection<Long>> ids = ArgumentCaptor.forClass(java.util.Collection.class);
        verify(content).findLatestPublicCovers(ids.capture());
        assertThat(ids.getValue()).containsExactly(20L);
    }

    @Test
    void 陪伴天数与H5名片同一个算法() {
        candidates(List.of(10L));
        withProfiles(pet(10L, 110L, "a10", NOW.minus(238, ChronoUnit.DAYS)));
        assertThat(service.recommendFor(VIEWER, 10, NOW).getFirst().companionDays()).isEqualTo(238);
        // 与 H5 名片那个纯函数逐字一致（两处算出不同天数，用户一对比就看得出来）。
        assertThat(PetRecommendationService.companionDays(NOW.minus(238, ChronoUnit.DAYS), NOW))
                .isEqualTo(com.tailtopia.profile.web.CardPageController
                        .companionDays(NOW.minus(238, ChronoUnit.DAYS), NOW));
    }

    @Test
    void 不下发对外分享token() {
        // 点击落点是站内访客入口（按 petId）；对外 token 不该给访客（AD-4 / Story 2.3）。
        assertThat(java.util.Arrays.stream(RecommendedPetResponse.class.getRecordComponents())
                .map(java.lang.reflect.RecordComponent::getName))
                .doesNotContain("cardToken", "token", "shareUrl");
    }

    // ===== AC2 的 L0 那一半：禁用项 =====

    @Test
    void 推荐链路里没有缓存也没有调度中间件() {
        // AC2：实时 SQL，不许加缓存层；允许的降级**只有** @Scheduled 落表，而本版没走降级。
        String src = readSource("src/main/java/com/tailtopia/profile/recommend/"
                + "PetRecommendationService.java");
        // 注释里可以谈论它们（正是为了警告后来人），所以只看**代码行**。
        String code = src.lines()
                .filter(l -> !l.stripLeading().startsWith("*") && !l.stripLeading().startsWith("//")
                        && !l.stripLeading().startsWith("/*"))
                .reduce("", (a, b) -> a + "\n" + b);
        for (String forbidden : List.of("@Cacheable", "Caffeine", "RedisTemplate", "@Scheduled")) {
            assertThat(code).as("推荐链路不得引入 " + forbidden + "（AC2）").doesNotContain(forbidden);
        }
    }

    @Test
    void 索引迁移覆盖了AC2点名的两个过滤维度() {
        String sql = readSource("src/main/resources/db/migration/"
                + "V20260915_1727__add_pet_recommendation_index.sql");
        // 「公开 Diary 帖的宠物 + 发布时间」与「宠物公开记录条数」两个维度共用一条部分索引：
        // 谓词下推之后索引里只剩 (pet_id, created_at) —— GROUP BY 键 + 聚合列。
        assertThat(sql).contains("idx_content_posts_pet_recommend")
                .contains("(pet_id, created_at DESC)")
                .contains("type = 'GROWTH_MOMENT'")
                .contains("visibility = 'PUBLIC'");
    }

    @Test
    void 互动量只数入池口径的帖子且不是逐候选相关子查询() {
        // 🔴 code-review 2026-09-15 抓到两处：
        //   ① 点赞数只按 pet_id 数 → 私密 / 已删 / DAILY 帖的点赞在影响公开推荐位排序
        //     （把高赞帖改成私密后仍稳坐第一）；
        //   ② 相关子查询形态 → 每个候选独立执行一次。
        // 改成一个预聚合 CTE 之后两处一起消：谓词与候选逐字相同，且只扫一遍。
        String src = readSource("src/main/java/com/tailtopia/content/repository/"
                + "ContentPostRepository.java");
        int start = src.indexOf("WITH candidates AS (");
        assertThat(start).as("推荐候选查询应是 CTE 形态").isGreaterThan(0);
        String sql = src.substring(start, src.indexOf("findRecommendablePets", start));

        int liked = sql.indexOf("liked AS (");
        assertThat(liked).as("互动量应预聚合成 liked CTE，而不是逐候选的相关子查询")
                .isGreaterThan(0);
        String likedPart = sql.substring(liked, sql.indexOf("SELECT c.pet_id", liked))
                .replace("p.", "");
        for (String predicate : List.of("type = 'GROWTH_MOMENT'", "visibility = 'PUBLIC'",
                "status = 'PUBLISHED'", "deleted_at IS NULL")) {
            assertThat(likedPart)
                    .as("点赞统计必须与候选口径同一套谓词，缺 " + predicate).contains(predicate);
        }
        // 范围收在候选集内，不扫全表点赞。
        assertThat(likedPart).contains("IN (SELECT pet_id FROM candidates)");
        // 不许回到老形态。
        assertThat(sql).doesNotContain("WHERE p2.pet_id = c.pet_id");
    }

    @Test
    void 同日分桶按WIB截断而不是会话时区() {
        // 🔴 code-review 2026-09-15：date(timestamptz) 隐式吃**数据库会话时区**（生产容器是 UTC），
        //    于是雅加达 00:00–07:00 发的帖被算进前一天 —— 那段时间里 AC1 的「同日按互动量」
        //    静默失效（实测 1 赞的排到 3 赞前面）。用户看到的「今天」是 WIB 的今天。
        String src = readSource("src/main/java/com/tailtopia/content/repository/"
                + "ContentPostRepository.java");
        int start = src.indexOf("WITH candidates AS (");
        String sql = src.substring(start, src.indexOf("findRecommendablePets", start));
        assertThat(sql).contains("date(c.last_at AT TIME ZONE 'Asia/Jakarta')");
        // 不许回到裸 date(c.last_at)。
        assertThat(sql.replace("date(c.last_at AT TIME ZONE 'Asia/Jakarta')", ""))
                .doesNotContain("date(c.last_at)");
        // 与运营配置时间那几处同一个口径。
        assertThat(com.tailtopia.shared.schedule.ScheduleWindow.WIB.getId())
                .isEqualTo("Asia/Jakarta");
    }

    @Test
    void 封面判据里jsonb类型守卫必须在取长度之前且查询与索引逐字相同() {
        // 🔴 code-review 2026-09-15：jsonb_array_length 对非数组 jsonb **抛错**（不是返回 null）。
        //    部分索引的条件是每次 INSERT 都要算的 → 库里出现一行非数组，这类行就再也插不进去，
        //    而 CREATE INDEX 本身也会失败 → 迁移中断 → 启动即拒。
        //    ⚠️ 两处谓词还必须一致，否则 planner 用不上那条部分索引（= 悄悄全表扫）。
        String src = readSource("src/main/java/com/tailtopia/content/repository/"
                + "ContentPostRepository.java");
        String sql = readSource("src/main/resources/db/migration/"
                + "V20260915_1727__add_pet_recommendation_index.sql");
        for (String text : List.of(src, sql)) {
            int typeAt = text.indexOf("jsonb_typeof(image_urls) = 'array'");
            int lenAt = text.indexOf("jsonb_array_length(image_urls) > 0");
            assertThat(typeAt).as("缺少 jsonb_typeof 守卫").isGreaterThan(0);
            assertThat(typeAt).as("jsonb_typeof 必须写在 jsonb_array_length 之前").isLessThan(lenAt);
        }
    }

    private static String readSource(String relative) {
        try {
            return java.nio.file.Files.readString(java.nio.file.Path.of(relative));
        } catch (java.io.IOException e) {
            throw new AssertionError("读不到 " + relative, e);
        }
    }
}
