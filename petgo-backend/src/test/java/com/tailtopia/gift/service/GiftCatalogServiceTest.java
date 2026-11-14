package com.tailtopia.gift.service;

import static com.tailtopia.gift.service.GiftTestFixtures.OWNER;
import static com.tailtopia.gift.service.GiftTestFixtures.PET;
import static com.tailtopia.gift.service.GiftTestFixtures.SENDER;
import static com.tailtopia.gift.service.GiftTestFixtures.exclusive;
import static com.tailtopia.gift.service.GiftTestFixtures.gift;
import static com.tailtopia.gift.service.GiftTestFixtures.offline;
import static com.tailtopia.gift.service.GiftTestFixtures.pet;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.tailtopia.auth.dto.AuthorView;
import com.tailtopia.auth.service.AccountQueryService;
import com.tailtopia.config.domain.PricingConfig;
import com.tailtopia.config.service.PlatformConfigService;
import com.tailtopia.gift.domain.GiftCatalogItem;
import com.tailtopia.gift.domain.GiftCatalogStatus;
import com.tailtopia.gift.domain.GiftCategory;
import com.tailtopia.gift.domain.GiftCategoryStatus;
import com.tailtopia.gift.dto.GiftCatalogResponse;
import com.tailtopia.gift.dto.GiftCatalogResponse.Category;
import com.tailtopia.gift.dto.GiftCatalogResponse.ReceivedFrom;
import com.tailtopia.gift.dto.GiftCatalogResponse.GiftItem;
import com.tailtopia.gift.port.GiftPetDirectory;
import com.tailtopia.gift.repository.GiftCatalogRepository;
import com.tailtopia.gift.repository.GiftCategoryRepository;
import com.tailtopia.gift.repository.GiftOrderRepository;
import com.tailtopia.growth.domain.GrowthLevel;
import com.tailtopia.growth.domain.GrowthTier;
import com.tailtopia.growth.domain.RuleCode;
import com.tailtopia.growth.service.GrowthLedgerService;
import com.tailtopia.growth.service.GrowthQueryService;
import com.tailtopia.shared.error.AppException;
import com.tailtopia.social.read.UserHideRelationReader;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * V1.4.0 gift Story 1.1 · AC4（L0）：目录分组、物种排序（PRD AC3a）、专属过滤与锁区块（PRD AC3）。
 * V1.5.0 gift-pack Story 1-1 · AC3.5：分类分组、senderExp、receivedFrom。
 */
class GiftCatalogServiceTest {

    private GiftCatalogRepository repo;
    private GiftPetDirectory pets;
    private UserHideRelationReader hides;
    private GrowthQueryService growth;
    private GiftCatalogService service;
    private GiftCategoryRepository categoryRepo;
    private GiftOrderRepository orderRepo;
    private GrowthLedgerService ledger;
    private AccountQueryService accounts;
    private final List<GiftCatalogItem> online = new ArrayList<>();
    private final List<GiftCategory> categories = new ArrayList<>();

    @BeforeEach
    void setUp() {
        repo = mock(GiftCatalogRepository.class);
        pets = mock(GiftPetDirectory.class);
        hides = mock(UserHideRelationReader.class);
        growth = mock(GrowthQueryService.class);
        PlatformConfigService config = mock(PlatformConfigService.class);
        PricingConfig pricing = mock(PricingConfig.class);
        when(pricing.getGiftQrisMinAmount()).thenReturn(10_000L);
        when(config.pricing()).thenReturn(pricing);
        when(repo.findByStatus(GiftCatalogStatus.ONLINE)).thenReturn(online);
        when(growth.getLevel(SENDER)).thenReturn(GrowthLevel.of(1, GrowthTier.NEWBIE));
        categoryRepo = mock(GiftCategoryRepository.class);
        orderRepo = mock(GiftOrderRepository.class);
        ledger = mock(GrowthLedgerService.class);
        accounts = mock(AccountQueryService.class);
        when(categoryRepo.findAllByStatusOrderBySortOrderAscIdAsc(GiftCategoryStatus.ONLINE)).thenReturn(categories);
        when(ledger.pointsForAmount(org.mockito.ArgumentMatchers.eq(RuleCode.GIFT_SENT),
                org.mockito.ArgumentMatchers.anyLong())).thenAnswer(inv -> (int) ((long) inv.getArgument(1) / 100));
        service = new GiftCatalogService(repo, new GiftRecipientGuard(pets, hides), growth, config, categoryRepo,
                orderRepo, ledger, accounts);

        // Rp1,000 档：只标狗(sort 0) / 通用(sort 5) / 只标猫(sort 9) / 通用(sort 1)；Rp5,000 档：猫狗皆可
        online.add(gift(1, 1_000, false, true, false, 0));
        online.add(gift(2, 1_000, false, false, false, 5));
        online.add(gift(3, 1_000, true, false, false, 9));
        online.add(gift(4, 1_000, false, false, false, 1));
        online.add(gift(5, 5_000, true, true, false, 0));
        // 两批专属：Lv5、Lv15
        online.add(exclusive(10, 20_000, 5, 0));
        online.add(exclusive(11, 30_000, 15, 0));
        online.add(exclusive(12, 25_000, 5, 1));
    }

    private static List<String> tokens(List<GiftItem> items) {
        return items.stream().map(GiftItem::giftToken).toList();
    }

    @Test
    void catFirstThenUniversalThenOthersWithinTier() {
        when(pets.pet(PET)).thenReturn(Optional.of(pet("CAT")));
        GiftCatalogResponse r = service.catalog(SENDER, PET, null);
        assertThat(r.tiers()).extracting(GiftCatalogResponse.Tier::price).containsExactly(1_000L, 5_000L);
        assertThat(tokens(r.tiers().get(0).gifts())).as("猫 → 通用(sort 升序) → 只标狗")
                .containsExactly("g3", "g4", "g2", "g1");
        assertThat(r.qrisMinAmount()).isEqualTo(10_000L);
        assertThat(r.recipient().petId()).isEqualTo(PET);
        assertThat(r.recipient().name()).isEqualTo("Mochi");
    }

    @Test
    void dogFirstForDog() {
        when(pets.pet(PET)).thenReturn(Optional.of(pet("DOG")));
        GiftCatalogResponse r = service.catalog(SENDER, PET, null);
        assertThat(tokens(r.tiers().get(0).gifts())).containsExactly("g1", "g4", "g2", "g3");
    }

    @Test
    void otherSpeciesSeesUniversalFirst() {
        when(pets.pet(PET)).thenReturn(Optional.of(pet("OTHER")));
        GiftCatalogResponse r = service.catalog(SENDER, PET, null);
        assertThat(tokens(r.tiers().get(0).gifts())).as("无 for_other 礼物：通用在前，其余按 sort_order / id")
                .containsExactly("g4", "g2", "g1", "g3");
    }

    @Test
    void sameSortOrderFallsBackToIdAscending() {
        online.clear();
        online.add(gift(9, 1_000, false, false, false, 0));
        online.add(gift(8, 1_000, false, false, false, 0));
        when(pets.pet(PET)).thenReturn(Optional.of(pet("CAT")));
        assertThat(tokens(service.catalog(SENDER, PET, null).tiers().get(0).gifts())).containsExactly("g8", "g9");
    }

    @Test
    void lv1SeesNoExclusiveAndLockAtFive() {
        when(pets.pet(PET)).thenReturn(Optional.of(pet("CAT")));
        GiftCatalogResponse r = service.catalog(SENDER, PET, null);
        assertThat(r.exclusiveGroups()).isEmpty();
        assertThat(r.lockBlock().requiredLevel()).isEqualTo(5);
        assertThat(r.tiers()).flatExtracting(GiftCatalogResponse.Tier::gifts).extracting(GiftItem::kind)
                .containsOnly("NORMAL");
    }

    @Test
    void lv5SeesFirstBatchAndLockAtFifteen() {
        when(growth.getLevel(SENDER)).thenReturn(GrowthLevel.of(5, GrowthTier.NEWBIE));
        when(pets.pet(PET)).thenReturn(Optional.of(pet("CAT")));
        GiftCatalogResponse r = service.catalog(SENDER, PET, null);
        assertThat(r.exclusiveGroups()).hasSize(1);
        assertThat(r.exclusiveGroups().get(0).minLevel()).isEqualTo(5);
        assertThat(tokens(r.exclusiveGroups().get(0).gifts())).containsExactly("g10", "g12");
        assertThat(r.lockBlock().requiredLevel()).isEqualTo(15);
    }

    @Test
    void lv15UnlocksAllAndLockBlockIsNull() {
        when(growth.getLevel(SENDER)).thenReturn(GrowthLevel.of(15, GrowthTier.NEWBIE));
        when(pets.pet(PET)).thenReturn(Optional.of(pet("CAT")));
        GiftCatalogResponse r = service.catalog(SENDER, PET, null);
        assertThat(r.exclusiveGroups()).extracting(GiftCatalogResponse.ExclusiveGroup::minLevel)
                .as("第一批在前").containsExactly(5, 15);
        assertThat(r.lockBlock()).isNull();
    }

    @Test
    void notParticipatingCountsAsLevelZero() {
        when(growth.getLevel(SENDER)).thenReturn(GrowthLevel.NOT_PARTICIPATING);
        when(pets.pet(PET)).thenReturn(Optional.of(pet("CAT")));
        GiftCatalogResponse r = service.catalog(SENDER, PET, null);
        assertThat(r.exclusiveGroups()).isEmpty();
        assertThat(r.lockBlock().requiredLevel()).isEqualTo(5);
    }

    @Test
    void offlineGiftsNeverAppear() {
        online.add(offline(gift(6, 1_000, true, false, false, 0)));
        when(pets.pet(PET)).thenReturn(Optional.of(pet("CAT")));
        GiftCatalogResponse r = service.catalog(SENDER, PET, null);
        assertThat(r.tiers()).flatExtracting(GiftCatalogResponse.Tier::gifts).extracting(GiftItem::giftToken)
                .doesNotContain("g6");
    }

    @Test
    void postIdResolvesFirstPetOfPost() {
        when(pets.petsOfPost(900L)).thenReturn(List.of(pet("DOG")));
        GiftCatalogResponse r = service.catalog(SENDER, null, 900L);
        assertThat(r.recipient().petId()).isEqualTo(PET);
        assertThat(r.recipient().petType()).isEqualTo("DOG");
    }

    @Test
    void exactlyOneOfPetIdOrPostId() {
        assertThatThrownBy(() -> service.catalog(SENDER, null, null)).isInstanceOf(AppException.class)
                .extracting(e -> ((AppException) e).getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThatThrownBy(() -> service.catalog(SENDER, PET, 900L)).isInstanceOf(AppException.class)
                .extracting(e -> ((AppException) e).getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    }

    @Test
    void recipientChecksSameAsSend() {
        when(pets.pet(PET)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.catalog(SENDER, PET, null))
                .extracting(e -> ((AppException) e).getType().toString()).asString().endsWith("gift-pet-gone");
        when(pets.petsOfPost(901L)).thenReturn(List.of());
        assertThatThrownBy(() -> service.catalog(SENDER, null, 901L))
                .extracting(e -> ((AppException) e).getStatus()).isEqualTo(HttpStatus.NOT_FOUND);

        when(pets.pet(PET)).thenReturn(Optional.of(pet("CAT")));
        when(hides.isBlocked(SENDER, OWNER)).thenReturn(true);
        assertThatThrownBy(() -> service.catalog(SENDER, PET, null))
                .extracting(e -> ((AppException) e).getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThatThrownBy(() -> service.catalog(OWNER, PET, null))
                .extracting(e -> ((AppException) e).getType().toString()).asString().endsWith("gift-own-pet");
    }

    @Test
    void responseDoesNotLeakInternalCatalogFields() {
        assertThat(GiftItem.class.getRecordComponents()).extracting(c -> c.getName())
                .doesNotContain("id", "sortOrder", "forCat", "forDog", "forOther");
    }

    // ---------- V1.5.0 gift-pack Story 1-1 ----------

    private static GiftCategory category(long id, String en, int sort) {
        GiftCategory c = GiftCategory.create(en, en + "-id", sort, null);
        ReflectionTestUtils.setField(c, "id", id);
        return c;
    }

    private static GiftCatalogItem inCategory(GiftCatalogItem g, long categoryId) {
        ReflectionTestUtils.setField(g, "categoryId", categoryId);
        return g;
    }

    private void twoCategories() {
        // Snacks(sort 10) 收 g1~g4，Treats(sort 20) 收 g5；Toys(sort 30) 为空
        categories.add(category(100, "Snacks", 10));
        categories.add(category(200, "Treats", 20));
        categories.add(category(300, "Toys", 30));
        for (GiftCatalogItem g : online) {
            if (g.getKind() == com.tailtopia.gift.domain.GiftKind.NORMAL) {
                inCategory(g, g.getPrice() == 1_000 ? 100 : 200);
            }
        }
    }

    @Test
    void categoriesGroupedSortedAndEmptyOmitted() {
        twoCategories();
        when(pets.pet(PET)).thenReturn(Optional.of(pet("CAT")));
        GiftCatalogResponse r = service.catalog(SENDER, PET, null);
        assertThat(r.categories()).extracting(Category::categoryId).containsExactly(100L, 200L);
        assertThat(tokens(r.categories().get(0).gifts())).as("组内排序沿用 orderFor").containsExactly("g3", "g4", "g2", "g1");
        assertThat(r.categories().get(0).nameEn()).isEqualTo("Snacks");
        assertThat(r.categories().get(0).nameId()).isEqualTo("Snacks-id");
        assertThat(r.categories()).flatExtracting(Category::gifts).extracting(GiftItem::kind).containsOnly("NORMAL");
        // 旧 tiers 不受影响
        assertThat(r.tiers()).extracting(GiftCatalogResponse.Tier::price).containsExactly(1_000L, 5_000L);
        assertThat(tokens(r.tiers().get(0).gifts())).containsExactly("g3", "g4", "g2", "g1");
    }

    @Test
    void offlineCategoryAndUncategorizedOnlyInTiers() {
        twoCategories();
        categories.removeIf(c -> c.getId() == 200L); // Treats 下线（不在在线列表里）
        inCategory(online.get(1), 999L); // g2 指向不存在 / 下线分类
        ReflectionTestUtils.setField(online.get(3), "categoryId", null); // g4 未归类
        when(pets.pet(PET)).thenReturn(Optional.of(pet("CAT")));
        GiftCatalogResponse r = service.catalog(SENDER, PET, null);
        assertThat(r.categories()).extracting(Category::categoryId).containsExactly(100L);
        assertThat(tokens(r.categories().get(0).gifts())).containsExactly("g3", "g1");
        assertThat(r.tiers()).flatExtracting(GiftCatalogResponse.Tier::gifts).extracting(GiftItem::giftToken)
                .contains("g2", "g4", "g5");
    }

    @Test
    void senderExpZeroWhenNotParticipating() {
        twoCategories();
        when(growth.getLevel(SENDER)).thenReturn(GrowthLevel.NOT_PARTICIPATING);
        when(pets.pet(PET)).thenReturn(Optional.of(pet("CAT")));
        GiftCatalogResponse r = service.catalog(SENDER, PET, null);
        assertThat(r.tiers()).flatExtracting(GiftCatalogResponse.Tier::gifts).extracting(GiftItem::senderExp)
                .containsOnly(0);
    }

    @Test
    void senderExpFromGiftSentRuleWhenParticipating() {
        twoCategories();
        when(growth.getLevel(SENDER)).thenReturn(GrowthLevel.of(5, GrowthTier.NEWBIE));
        when(pets.pet(PET)).thenReturn(Optional.of(pet("CAT")));
        GiftCatalogResponse r = service.catalog(SENDER, PET, null);
        assertThat(r.tiers().get(0).gifts()).extracting(GiftItem::senderExp).containsOnly(10);
        assertThat(r.tiers().get(1).gifts()).extracting(GiftItem::senderExp).containsOnly(50);
        assertThat(r.exclusiveGroups().get(0).gifts()).extracting(GiftItem::senderExp).containsExactly(200, 250);
        org.mockito.Mockito.verify(growth, org.mockito.Mockito.times(1)).getLevel(SENDER);
    }

    @Test
    void receivedFromTopThreeByRecencyWithMoreAndDeleted() {
        twoCategories();
        Instant t = Instant.parse("2026-11-01T00:00:00Z");
        when(orderRepo.deliveredSendersOfPet(PET)).thenReturn(List.of(
                new Object[] {1L, 501L, Timestamp.from(t.plusSeconds(10))},
                new Object[] {1L, 502L, Timestamp.from(t.plusSeconds(40))},
                new Object[] {1L, 503L, Timestamp.from(t.plusSeconds(30))},
                new Object[] {1L, 504L, Timestamp.from(t.plusSeconds(20))},
                new Object[] {5L, 501L, Timestamp.from(t)}));
        when(accounts.findAuthorViewsWithoutTags(org.mockito.ArgumentMatchers.anyCollection())).thenReturn(Map.of(
                501L, new AuthorView(501L, "Ana", null, false, List.of()),
                502L, new AuthorView(502L, "Budi", null, false, List.of()),
                503L, new AuthorView(503L, null, null, true, List.of()),
                504L, new AuthorView(504L, "Dewi", null, false, List.of())));
        when(pets.pet(PET)).thenReturn(Optional.of(pet("CAT")));
        GiftCatalogResponse r = service.catalog(SENDER, PET, null);
        List<GiftItem> all = r.tiers().stream().flatMap(x -> x.gifts().stream()).toList();
        ReceivedFrom g1 = all.stream().filter(g -> g.giftToken().equals("g1")).findFirst().orElseThrow().receivedFrom();
        assertThat(g1.senders()).extracting(GiftCatalogResponse.Sender::nickname).containsExactly("Budi", null, "Dewi");
        assertThat(g1.senders().get(1).deleted()).isTrue();
        assertThat(g1.more()).isEqualTo(1);
        ReceivedFrom g5 = all.stream().filter(g -> g.giftToken().equals("g5")).findFirst().orElseThrow().receivedFrom();
        assertThat(g5.senders()).extracting(GiftCatalogResponse.Sender::nickname).containsExactly("Ana");
        assertThat(g5.more()).isZero();
        assertThat(all.stream().filter(g -> g.giftToken().equals("g2")).findFirst().orElseThrow().receivedFrom())
                .isNull();
        org.mockito.Mockito.verify(orderRepo, org.mockito.Mockito.times(1)).deliveredSendersOfPet(PET);
    }

    @Test
    void newGiftItemFieldsAppendedAtEnd() {
        assertThat(GiftItem.class.getRecordComponents()).extracting(c -> c.getName()).containsExactly(
                "giftToken", "nameEn", "nameId", "iconUrl", "price", "kind", "minLevel", "senderExp", "receivedFrom");
        assertThat(GiftCatalogResponse.class.getRecordComponents()).extracting(c -> c.getName()).containsExactly(
                "recipient", "qrisMinAmount", "tiers", "exclusiveGroups", "lockBlock", "categories");
    }
}
