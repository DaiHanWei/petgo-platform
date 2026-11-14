package com.tailtopia.profile.recommend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tailtopia.auth.service.AccountQueryService;
import com.tailtopia.content.service.ContentService;
import com.tailtopia.content.service.ContentService.RecommendFilter;
import com.tailtopia.content.service.ContentService.RecommendFilterMode;
import com.tailtopia.profile.domain.PetProfile;
import com.tailtopia.profile.domain.PetType;
import com.tailtopia.profile.repository.PetProfileRepository;
import com.tailtopia.social.read.PetFollowReader;
import com.tailtopia.social.read.UserHideRelationReader;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * L0：V1.5.0 onboarding-recall Story 1.1 · AC1 ~ AC6 —— 三段 keyset、跨段续取、match、封面帖、游客版。
 *
 * <p>段内排序在 SQL 里（L1 验）；这里钉住「按查看者默认宠物算出的段序列」「每段传给 content 的过滤参数」
 * 「段到底才续下一段」「游标带段号」。
 */
class PetRecommendationTierTest {

    private static final long VIEWER = 1L;
    private static final Instant NOW = Instant.parse("2026-11-14T12:00:00Z");

    private ContentService content;
    private PetProfileRepository profiles;
    private AccountQueryService accounts;
    private UserHideRelationReader hideRelations;
    private PetFollowReader petFollows;
    private PetRecommendationService service;

    /** 每段的候选（按 mode 分）。 */
    private final Map<RecommendFilterMode, List<ContentService.RecommendablePet>> byMode =
            new EnumMap<>(RecommendFilterMode.class);
    private final Map<Long, PetProfile> pets = new HashMap<>();

    @BeforeEach
    void setUp() {
        content = mock(ContentService.class);
        profiles = mock(PetProfileRepository.class);
        accounts = mock(AccountQueryService.class);
        hideRelations = mock(UserHideRelationReader.class);
        petFollows = mock(PetFollowReader.class);
        service = new PetRecommendationService(content, profiles, accounts, hideRelations, petFollows);
        when(petFollows.followedAmong(anyLong(), anyCollection())).thenReturn(Set.of());
        when(hideRelations.hiddenEitherWay(anyLong(), anyCollection())).thenReturn(Set.of());
        when(accounts.activeIdsAmong(anyCollection())).thenAnswer(
                inv -> new java.util.HashSet<>(inv.<java.util.Collection<Long>>getArgument(0)));
        when(content.findRecommendablePets(any(), anyInt(), anyInt(), any(), any())).thenAnswer(inv -> {
            RecommendFilter f = inv.getArgument(4);
            ContentService.RecommendCursor after = inv.getArgument(3);
            List<ContentService.RecommendablePet> rows = byMode.getOrDefault(f.mode(), List.of());
            if (after == null) {
                return rows;
            }
            int idx = -1;
            for (int i = 0; i < rows.size(); i++) {
                if (rows.get(i).petId() == after.petId()) {
                    idx = i;
                }
            }
            return rows.subList(idx + 1, rows.size());
        });
        when(profiles.findAllById(anyCollection())).thenAnswer(inv -> {
            List<PetProfile> out = new ArrayList<>();
            for (Long id : inv.<java.util.Collection<Long>>getArgument(0)) {
                if (pets.containsKey(id)) {
                    out.add(pets.get(id));
                }
            }
            return out;
        });
    }

    private static PetProfile profile(long id, long owner, PetType type, String breed) {
        PetProfile p = PetProfile.create(owner, type, "P" + id, "a" + id, breed,
                LocalDate.of(2024, 1, 1), null, "tok" + id);
        set(p, "id", id);
        set(p, "createdAt", NOW.minusSeconds(86400));
        return p;
    }

    private static void set(PetProfile p, String name, Object value) {
        try {
            var f = PetProfile.class.getDeclaredField(name);
            f.setAccessible(true);
            f.set(p, value);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    /** 往某段塞候选宠物（id 依次，owner = 1000 + id）。 */
    private void tier(RecommendFilterMode mode, long... ids) {
        List<ContentService.RecommendablePet> rows = new ArrayList<>();
        int i = 0;
        for (long id : ids) {
            rows.add(new ContentService.RecommendablePet(id, NOW.minusSeconds(60L * ++i), 5L, 10L));
            pets.putIfAbsent(id, profile(id, 1000 + id, PetType.CAT, null));
        }
        byMode.put(mode, rows);
    }

    private void viewerHas(PetProfile... own) {
        when(profiles.findAllByOwnerIdOrderByCreatedAtAscIdAsc(VIEWER)).thenReturn(List.of(own));
    }

    @Test
    void 有品种按同品种同物种他物种三段且match依次() {
        viewerHas(profile(500, VIEWER, PetType.DOG, " Corgi "));
        tier(RecommendFilterMode.SAME_BREED, 11, 12);
        tier(RecommendFilterMode.SAME_SPECIES_OTHER_BREED, 21);
        tier(RecommendFilterMode.OTHER_SPECIES, 31, 32);

        RecommendedPetResponse.Page page = service.pageFor(VIEWER, 10, null, NOW);
        assertThat(page.items()).extracting(RecommendedPetResponse::petId).containsExactly(11L, 12L, 21L, 31L, 32L);
        assertThat(page.items()).extracting(RecommendedPetResponse::match).containsExactly(
                RecommendMatch.SAME_BREED, RecommendMatch.SAME_BREED, RecommendMatch.SAME_SPECIES,
                RecommendMatch.GLOBAL, RecommendMatch.GLOBAL);
        assertThat(page.hasMore()).isFalse();

        ArgumentCaptor<RecommendFilter> filters = ArgumentCaptor.forClass(RecommendFilter.class);
        verify(content, org.mockito.Mockito.times(3))
                .findRecommendablePets(any(), anyInt(), anyInt(), any(), filters.capture());
        assertThat(filters.getAllValues()).containsExactly(
                new RecommendFilter(RecommendFilterMode.SAME_BREED, "DOG", "corgi"),
                new RecommendFilter(RecommendFilterMode.SAME_SPECIES_OTHER_BREED, "DOG", "corgi"),
                new RecommendFilter(RecommendFilterMode.OTHER_SPECIES, "DOG", null));
    }

    @Test
    void 默认宠物无品种跳过同品种段() {
        viewerHas(profile(500, VIEWER, PetType.DOG, "  "));
        tier(RecommendFilterMode.SAME_SPECIES_OTHER_BREED, 21);
        tier(RecommendFilterMode.OTHER_SPECIES, 31);

        RecommendedPetResponse.Page page = service.pageFor(VIEWER, 10, null, NOW);
        assertThat(page.items()).extracting(RecommendedPetResponse::match)
                .containsExactly(RecommendMatch.SAME_SPECIES, RecommendMatch.GLOBAL);
        ArgumentCaptor<RecommendFilter> filters = ArgumentCaptor.forClass(RecommendFilter.class);
        verify(content, org.mockito.Mockito.times(2))
                .findRecommendablePets(any(), anyInt(), anyInt(), any(), filters.capture());
        assertThat(filters.getAllValues()).extracting(RecommendFilter::mode).containsExactly(
                RecommendFilterMode.SAME_SPECIES_OTHER_BREED, RecommendFilterMode.OTHER_SPECIES);
        assertThat(filters.getAllValues().getFirst().breedNorm()).isNull();
    }

    @Test
    void 没宠物只看全站且不加物种条件() {
        tier(RecommendFilterMode.ANY, 31, 32);
        RecommendedPetResponse.Page page = service.pageFor(VIEWER, 10, null, NOW);
        assertThat(page.items()).extracting(RecommendedPetResponse::match)
                .containsOnly(RecommendMatch.GLOBAL);
        verify(content).findRecommendablePets(any(), anyInt(), anyInt(), any(), eq(RecommendFilter.ANY));
    }

    @Test
    void 默认宠物按DefaultPetPolicy取最早ACTIVE() {
        PetProfile readOnly = profile(500, VIEWER, PetType.CAT, "Persia");
        set(readOnly, "status", com.tailtopia.profile.domain.PetStatus.READ_ONLY);
        viewerHas(readOnly, profile(501, VIEWER, PetType.DOG, "Corgi"));
        tier(RecommendFilterMode.SAME_BREED, 11);
        service.pageFor(VIEWER, 10, null, NOW);
        ArgumentCaptor<RecommendFilter> filters = ArgumentCaptor.forClass(RecommendFilter.class);
        verify(content, org.mockito.Mockito.atLeastOnce())
                .findRecommendablePets(any(), anyInt(), anyInt(), any(), filters.capture());
        assertThat(filters.getAllValues().getFirst())
                .isEqualTo(new RecommendFilter(RecommendFilterMode.SAME_BREED, "DOG", "corgi"));
    }

    @Test
    void 翻页跨段且第二页从段1第3只续取() {
        viewerHas(profile(500, VIEWER, PetType.DOG, "Corgi"));
        tier(RecommendFilterMode.SAME_BREED, 11, 12);
        tier(RecommendFilterMode.SAME_SPECIES_OTHER_BREED, 21, 22, 23, 24);
        tier(RecommendFilterMode.OTHER_SPECIES);

        RecommendedPetResponse.Page first = service.pageFor(VIEWER, 4, null, NOW);
        assertThat(first.items()).extracting(RecommendedPetResponse::petId).containsExactly(11L, 12L, 21L, 22L);
        assertThat(first.hasMore()).isTrue();
        PetRecommendCursor cursor = PetRecommendCursor.decodeOrNull(first.nextCursor());
        assertThat(cursor.tier()).isEqualTo(1);
        assertThat(cursor.petId()).isEqualTo(22L);

        RecommendedPetResponse.Page second = service.pageFor(VIEWER, 4, cursor, NOW);
        assertThat(second.items()).extracting(RecommendedPetResponse::petId).containsExactly(23L, 24L);
        assertThat(second.hasMore()).isFalse();
        assertThat(second.nextCursor()).isNull();
    }

    @Test
    void 正好取完最后一段最后一行时hasMore为false() {
        tier(RecommendFilterMode.ANY, 31, 32);
        RecommendedPetResponse.Page page = service.pageFor(VIEWER, 2, null, NOW);
        assertThat(page.items()).hasSize(2);
        assertThat(page.hasMore()).isFalse();
    }

    @Test
    void 排除口径不变且只读宠物与同主人多只照常出现() {
        viewerHas(profile(500, VIEWER, PetType.CAT, null));
        tier(RecommendFilterMode.SAME_SPECIES_OTHER_BREED, 21, 22, 23, 24, 25, 26);
        pets.put(21L, profile(21, VIEWER, PetType.CAT, null));          // 自己的
        pets.put(22L, profile(22, 2000, PetType.CAT, null));             // 已追更
        PetProfile noAvatar = profile(23, 2001, PetType.CAT, null);
        set(noAvatar, "avatarUrl", null);
        pets.put(23L, noAvatar);                                          // 无头像
        PetProfile ro = profile(24, 3000, PetType.CAT, null);
        set(ro, "status", com.tailtopia.profile.domain.PetStatus.READ_ONLY);
        pets.put(24L, ro);                                                // 只读 → 照常
        pets.put(25L, profile(25, 3000, PetType.CAT, null));             // 同主人第二只 → 照常
        pets.put(26L, profile(26, 4000, PetType.CAT, null));             // 主人拉黑
        when(petFollows.followedAmong(eq(VIEWER), anyCollection())).thenReturn(Set.of(22L));
        when(hideRelations.hiddenEitherWay(eq(VIEWER), anyCollection())).thenReturn(Set.of(4000L));

        assertThat(service.pageFor(VIEWER, 10, null, NOW).items())
                .extracting(RecommendedPetResponse::petId).containsExactly(24L, 25L);
    }

    @Test
    void 封面帖字段同源且已赞一次批量() {
        tier(RecommendFilterMode.ANY, 31, 32);
        pets.put(31L, profile(31, 1031, PetType.DOG, " Corgi "));
        when(content.findLatestPublicCoverPosts(anyCollection())).thenReturn(Map.of(
                31L, new ContentService.CoverPost(9001L, "https://cdn/p.jpg", 12L)));
        when(content.likedAmong(eq(VIEWER), anyCollection())).thenReturn(Set.of(9001L));

        List<RecommendedPetResponse> items = service.pageFor(VIEWER, 10, null, NOW).items();
        RecommendedPetResponse a = items.get(0);
        assertThat(a.coverPostId()).isEqualTo(9001L);
        assertThat(a.coverLikeCount()).isEqualTo(12L);
        assertThat(a.coverLikedByViewer()).isTrue();
        assertThat(a.coverImageUrl()).startsWith("https://cdn/p.jpg?");
        assertThat(a.breed()).isEqualTo("Corgi");
        RecommendedPetResponse b = items.get(1);
        assertThat(b.coverPostId()).isNull();
        assertThat(b.coverImageUrl()).isNull();
        assertThat(b.coverLikeCount()).isZero();
        assertThat(b.coverLikedByViewer()).isFalse();
        assertThat(b.breed()).isNull();
        verify(content, org.mockito.Mockito.times(1)).likedAmong(eq(VIEWER), anyCollection());
    }

    @Test
    void 游客版只取全站段跳过查看者维度过滤且不查已赞() {
        tier(RecommendFilterMode.ANY, 31, 32);
        when(content.findLatestPublicCoverPosts(anyCollection())).thenReturn(Map.of(
                31L, new ContentService.CoverPost(9001L, "https://cdn/p.jpg", 3L)));

        List<RecommendedPetResponse> items = service.publicFirstPage(6, NOW);
        assertThat(items).extracting(RecommendedPetResponse::petId).containsExactly(31L, 32L);
        assertThat(items).allSatisfy(r -> {
            assertThat(r.coverLikedByViewer()).isFalse();
            assertThat(r.match()).isEqualTo(RecommendMatch.GLOBAL);
        });
        verify(content).findRecommendablePets(any(), anyInt(), anyInt(), eq(null), eq(RecommendFilter.ANY));
        verify(hideRelations, never()).hiddenEitherWay(anyLong(), anyCollection());
        verify(petFollows, never()).followedAmong(anyLong(), anyCollection());
        verify(content, never()).likedAmong(anyLong(), anyCollection());
        verify(profiles, never()).findAllByOwnerIdOrderByCreatedAtAscIdAsc(anyLong());
    }

    @Test
    void 游客版仍排除无头像与owner无效() {
        tier(RecommendFilterMode.ANY, 31, 32);
        PetProfile noAvatar = profile(31, 1031, PetType.CAT, null);
        set(noAvatar, "avatarUrl", "");
        pets.put(31L, noAvatar);
        when(accounts.activeIdsAmong(anyCollection())).thenReturn(Set.of());
        assertThat(service.publicFirstPage(6, NOW)).isEmpty();
    }

    @Test
    void 游客版limit上限10() {
        long[] ids = new long[30];
        for (int i = 0; i < 30; i++) {
            ids[i] = 100 + i;
        }
        tier(RecommendFilterMode.ANY, ids);
        assertThat(service.publicFirstPage(50, NOW)).hasSize(10);
    }

    @Test
    void 旧游标对没宠物查看者按keyset续取() {
        tier(RecommendFilterMode.ANY, 31, 32, 33);
        PetRecommendCursor legacy = new PetRecommendCursor(10L, NOW.minusSeconds(60), 31L);
        assertThat(service.pageFor(VIEWER, 10, legacy, NOW).items())
                .extracting(RecommendedPetResponse::petId).containsExactly(32L, 33L);
    }

    @Test
    void 物种匹配收口在speciesKeyOf() {
        assertThat(PetRecommendationService.speciesKeyOf(profile(1, 1, PetType.OTHER, null))).isEqualTo("OTHER");
        assertThat(PetRecommendationService.breedNorm(profile(1, 1, PetType.DOG, "  Golden Retriever ")))
                .isEqualTo("golden retriever");
        assertThat(PetRecommendationService.breedNorm(profile(1, 1, PetType.DOG, ""))).isNull();
    }
}
