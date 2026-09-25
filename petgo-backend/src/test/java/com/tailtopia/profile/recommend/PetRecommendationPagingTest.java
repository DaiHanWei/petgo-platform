package com.tailtopia.profile.recommend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * L0：推荐集合页的翻页（V1.3.0 batch-b1 Story 4.3 · AC3）。
 *
 * <h2>这里验的是「游标是不是整个排序键」与「hasMore 会不会提前断页」</h2>
 * 两者都是翻页的经典事故点，且都能在 L0 验：
 * <ul>
 *   <li>游标只带 petId → 第二页从一个与排序无关的位置开始（重复 + 永远刷不到）；</li>
 *   <li>hasMore 只看「这一页装满没」→ 一页里被过滤掉几个就再也翻不动了。</li>
 * </ul>
 * ❌ 真的「第二页接着第一页」要靠 SQL 的行比较，只有真库能验（L1）——
 * 这里能做的是钉住「传下去的游标确实是那一行的三元组」。
 */
class PetRecommendationPagingTest {

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
        when(accounts.activeIdsAmong(anyCollection()))
                .thenAnswer(inv -> new java.util.HashSet<>(
                        inv.<java.util.Collection<Long>>getArgument(0)));
    }

    /** 造 n 行候选，互动量与时刻都各不相同（好让游标三元组可辨认）。 */
    private List<ContentService.RecommendablePet> rows(int n) {
        List<ContentService.RecommendablePet> rows = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            rows.add(new ContentService.RecommendablePet(
                    10L + i, NOW.minusSeconds(60L * (i + 1)), 5L, 100L - i));
        }
        return rows;
    }

    private void candidates(List<ContentService.RecommendablePet> rows) {
        when(content.findRecommendablePets(any(), anyInt(), anyInt(), any())).thenReturn(rows);
    }

    private PetProfile pet(long petId, long ownerId, String avatarUrl) {
        PetProfile p = PetProfile.create(ownerId, PetType.CAT, "P" + petId, avatarUrl,
                null, LocalDate.of(2024, 1, 1), null, "tok" + petId);
        setField(p, "id", petId);
        setField(p, "createdAt", NOW.minusSeconds(86400));
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

    private void withProfilesFor(List<ContentService.RecommendablePet> rows) {
        List<PetProfile> pets = new ArrayList<>();
        for (ContentService.RecommendablePet r : rows) {
            pets.add(pet(r.petId(), 1000 + r.petId(), "a" + r.petId()));
        }
        when(profiles.findAllById(anyCollection())).thenReturn(pets);
    }

    // ===== 游标 =====

    @Test
    void 游标是整个排序键而不是最后那只宠物的id() {
        // 🔴 只带 petId 的表现是「第二页从一个与排序无关的位置开始」：
        //    重复第一页看过的宠物，同时另一批永远刷不到。
        List<ContentService.RecommendablePet> rows = rows(3);
        candidates(rows);
        withProfilesFor(rows);

        RecommendedPetResponse.Page page = service.pageFor(VIEWER, 3, null, NOW);
        assertThat(page.hasMore()).isTrue();

        PetRecommendCursor decoded = PetRecommendCursor.decodeOrNull(page.nextCursor());
        ContentService.RecommendablePet last = rows.get(2);
        assertThat(decoded).isNotNull();
        assertThat(decoded.petId()).isEqualTo(last.petId());
        assertThat(decoded.interactions()).isEqualTo(last.interactions());
        assertThat(decoded.lastPostedAt()).isEqualTo(last.lastPostedAt());
    }

    @Test
    void 游标原样传到content侧的取数上() {
        List<ContentService.RecommendablePet> rows = rows(1);
        candidates(rows);
        withProfilesFor(rows);
        PetRecommendCursor cursor = new PetRecommendCursor(42L, NOW.minusSeconds(999), 77L);

        service.pageFor(VIEWER, 10, cursor, NOW);

        ArgumentCaptor<ContentService.RecommendCursor> passed =
                ArgumentCaptor.forClass(ContentService.RecommendCursor.class);
        verify(content).findRecommendablePets(any(), anyInt(), anyInt(), passed.capture());
        assertThat(passed.getValue()).isNotNull();
        assertThat(passed.getValue().interactions()).isEqualTo(42L);
        assertThat(passed.getValue().petId()).isEqualTo(77L);
        assertThat(passed.getValue().lastPostedAt()).isEqualTo(NOW.minusSeconds(999));
    }

    @Test
    void 第一页不带游标() {
        List<ContentService.RecommendablePet> rows = rows(1);
        candidates(rows);
        withProfilesFor(rows);
        service.pageFor(VIEWER, 10, null, NOW);
        ArgumentCaptor<ContentService.RecommendCursor> passed =
                ArgumentCaptor.forClass(ContentService.RecommendCursor.class);
        verify(content).findRecommendablePets(any(), anyInt(), anyInt(), passed.capture());
        assertThat(passed.getValue()).isNull();
    }

    @Test
    void 游标取自最后看过的那一行而不是最后返回的那张卡() {
        // 被过滤掉的宠物是**确定性排除**（没头像/注销/拉黑/档案已删），
        // 下一页再扫一遍只会再排除一次 —— 所以游标越过它们是对的，也更省。
        // ⚠️ 造一个「池子给满但只有前几只能出卡」的局面，好让循环真的走到池底。
        int pool = PetRecommendationService.MIN_CANDIDATE_POOL;
        List<ContentService.RecommendablePet> rows = rows(pool);
        candidates(rows);
        List<PetProfile> pets = new ArrayList<>();
        for (int i = 0; i < pool; i++) {
            pets.add(pet(10L + i, 1010L + i, i < 3 ? "a" + i : null)); // 只有前 3 只有头像
        }
        when(profiles.findAllById(anyCollection())).thenReturn(pets);

        RecommendedPetResponse.Page page = service.pageFor(VIEWER, 10, null, NOW);
        assertThat(page.items()).extracting(RecommendedPetResponse::petId)
                .containsExactly(10L, 11L, 12L);
        // 🔴 游标是**池底那一行**（它没头像、没出卡），而不是最后返回的那张卡（12）——
        //    取成 12 的表现是下一页把中间那 37 只必然被过滤的又扫一遍。
        assertThat(PetRecommendCursor.decodeOrNull(page.nextCursor()).petId())
                .isEqualTo(10L + pool - 1);
        assertThat(page.hasMore()).isTrue();
    }

    // ===== hasMore =====

    @Test
    void 这一页装满了就还有更多() {
        List<ContentService.RecommendablePet> rows = rows(5);
        candidates(rows);
        withProfilesFor(rows);
        RecommendedPetResponse.Page page = service.pageFor(VIEWER, 2, null, NOW);
        assertThat(page.items()).hasSize(2);
        assertThat(page.hasMore()).isTrue();
        assertThat(page.nextCursor()).isNotNull();
    }

    @Test
    void 候选池没给满且没装满这一页就是到底了() {
        List<ContentService.RecommendablePet> rows = rows(3);
        candidates(rows);
        withProfilesFor(rows);
        RecommendedPetResponse.Page page = service.pageFor(VIEWER, 10, null, NOW);
        assertThat(page.items()).hasSize(3);
        assertThat(page.hasMore()).isFalse();
        assertThat(page.nextCursor()).isNull();
    }

    @Test
    void 池子给满了但被过滤吃掉大半时不许提前断页() {
        // 🔴 只看「这一页装满没」的表现是「一页里被过滤掉几个就再也翻不动了」——
        //    而池子越往后，拉黑/注销的比例并不会降低。
        int pool = PetRecommendationService.MIN_CANDIDATE_POOL;
        List<ContentService.RecommendablePet> rows = rows(pool);
        candidates(rows);
        // 只有第一只有头像 → 这一页只出 1 张卡，远没装满。
        List<PetProfile> pets = new ArrayList<>();
        pets.add(pet(10L, 1010L, "a10"));
        for (int i = 1; i < pool; i++) {
            pets.add(pet(10L + i, 1010L + i, null));
        }
        when(profiles.findAllById(anyCollection())).thenReturn(pets);

        RecommendedPetResponse.Page page = service.pageFor(VIEWER, 10, null, NOW);
        assertThat(page.items()).hasSize(1);
        assertThat(page.hasMore()).isTrue();
        assertThat(page.nextCursor()).isNotNull();
    }

    @Test
    void 一整页全被过滤光也要带着游标回空页() {
        // 🛡 客户端判「到底」只看 hasMore —— 空 items 不等于到底。
        int pool = PetRecommendationService.MIN_CANDIDATE_POOL;
        List<ContentService.RecommendablePet> rows = rows(pool);
        candidates(rows);
        List<PetProfile> pets = new ArrayList<>();
        for (int i = 0; i < pool; i++) {
            pets.add(pet(10L + i, 1010L + i, null)); // 全员没头像
        }
        when(profiles.findAllById(anyCollection())).thenReturn(pets);

        RecommendedPetResponse.Page page = service.pageFor(VIEWER, 10, null, NOW);
        assertThat(page.items()).isEmpty();
        assertThat(page.hasMore()).isTrue();
        assertThat(page.nextCursor()).isNotNull();
    }

    @Test
    void 候选一条都没有时是干净的最后一页() {
        candidates(List.of());
        RecommendedPetResponse.Page page = service.pageFor(VIEWER, 10, null, NOW);
        assertThat(page.items()).isEmpty();
        assertThat(page.hasMore()).isFalse();
        assertThat(page.nextCursor()).isNull();
    }

    // ===== 游标编解码 =====

    @Test
    void 游标编解码往返一致且是base64url不可枚举串() {
        PetRecommendCursor c = new PetRecommendCursor(37L,
                Instant.parse("2026-09-15T03:04:05.123456Z"), 4242L);
        String token = c.encode();
        // 🔒 不该能一眼看出里面是什么（与 FeedCursor / KeysetCursor 同一形态）。
        assertThat(token).doesNotContain(":").doesNotContain("4242");
        assertThat(PetRecommendCursor.decodeOrNull(token)).isEqualTo(c);
    }

    @Test
    void 游标保留微秒精度() {
        // 截断到毫秒会让「同刻」的判定连自己都对不上（KeysetCursor 记下的同一条教训）。
        Instant micros = Instant.parse("2026-09-15T03:04:05.123456Z");
        PetRecommendCursor back = PetRecommendCursor.decodeOrNull(
                new PetRecommendCursor(1L, micros, 2L).encode());
        assertThat(back.lastPostedAt()).isEqualTo(micros);
    }

    @Test
    void 坏游标当第一页处理而不是抛() {
        // 🔴 游标是客户端传回来的，坏值让整页 400/500 是把用户锁在门外。
        for (String bad : List.of("", "   ", "not-base64!!", "YWJj", "MTox")) {
            assertThat(PetRecommendCursor.decodeOrNull(bad)).as("坏游标 [" + bad + "] 应回 null")
                    .isNull();
        }
        assertThat(PetRecommendCursor.decodeOrNull(null)).isNull();
    }

    @Test
    void 翻页不改变每页的过滤口径() {
        // 拉黑 / 注销 / 没头像那几条在**每一页**都要判 —— 第二页少判一条就是漏。
        List<ContentService.RecommendablePet> rows = rows(3);
        candidates(rows);
        withProfilesFor(rows);
        service.pageFor(VIEWER, 3, new PetRecommendCursor(1L, NOW, 9L), NOW);
        verify(accounts).activeIdsAmong(anyCollection());
        verify(hideRelations).hiddenEitherWay(anyLong(), anyCollection());
    }
}
