package com.tailtopia.gift.service;

import com.tailtopia.auth.dto.AuthorView;
import com.tailtopia.auth.service.AccountQueryService;
import com.tailtopia.config.service.PlatformConfigService;
import com.tailtopia.gift.domain.GiftCatalogItem;
import com.tailtopia.gift.domain.GiftCatalogStatus;
import com.tailtopia.gift.domain.GiftCategory;
import com.tailtopia.gift.domain.GiftCategoryStatus;
import com.tailtopia.gift.domain.GiftKind;
import com.tailtopia.gift.dto.GiftCatalogResponse;
import com.tailtopia.gift.dto.GiftCatalogResponse.Category;
import com.tailtopia.gift.dto.GiftCatalogResponse.ExclusiveGroup;
import com.tailtopia.gift.dto.GiftCatalogResponse.GiftItem;
import com.tailtopia.gift.dto.GiftCatalogResponse.LockBlock;
import com.tailtopia.gift.dto.GiftCatalogResponse.ReceivedFrom;
import com.tailtopia.gift.dto.GiftCatalogResponse.Recipient;
import com.tailtopia.gift.dto.GiftCatalogResponse.Sender;
import com.tailtopia.gift.dto.GiftCatalogResponse.Tier;
import com.tailtopia.gift.port.GiftPetDirectory.GiftPet;
import com.tailtopia.gift.repository.GiftCatalogRepository;
import com.tailtopia.gift.repository.GiftCategoryRepository;
import com.tailtopia.gift.repository.GiftOrderRepository;
import com.tailtopia.growth.domain.GrowthLevel;
import com.tailtopia.growth.domain.RuleCode;
import com.tailtopia.growth.service.GrowthLedgerService;
import com.tailtopia.growth.service.GrowthQueryService;
import com.tailtopia.profile.domain.PetType;
import com.tailtopia.shared.error.AppException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * App 礼物目录（V1.4.0 gift Story 1.1 · AC4 · AD-10 · PRD AC3 / AC3a）。
 *
 * <ul>
 *   <li>入参 {@code petId} / {@code postId} 二选一（{@code postId} 经 {@code GiftPetDirectory.petsOfPost} 取第一只；
 *       <b>已弃用</b>，gift Story 4.1 起 App 一律走 {@code petId}，保留只为兼容 1.4.0 早期包）；收礼宠物校验与发起同口径
 *       （{@link GiftRecipientGuard}）。</li>
 *   <li>组内排序：适用收礼宠物物种 → 通用（三个物种标记皆 false）→ 其他；同组 {@code sort_order ASC, id ASC}。</li>
 *   <li>专属礼物按 {@link GrowthQueryService#getLevel} 过滤（{@code participating = false} 视为 level 0），
 *       只放 {@code min_level <= level}；{@code lockBlock.requiredLevel} = 未解锁专属礼物里最小的 {@code min_level}。</li>
 *   <li>V1.5.0 gift-pack Story 1-1（D-3）：在同一份已排序列表上再按在线分类分一次组（{@code categories}）；
 *       每件礼物带 {@code senderExp} 与 {@code receivedFrom}。旧字段（{@code tiers} 等）逐字不变。</li>
 * </ul>
 */
@Service
public class GiftCatalogService {

    private static final Logger log = LoggerFactory.getLogger(GiftCatalogService.class);
    /** 「谁送过」最多列几人（其余计入 {@code more}）。 */
    static final int RECEIVED_FROM_LIMIT = 3;

    private final GiftCatalogRepository catalog;
    private final GiftRecipientGuard recipients;
    private final GrowthQueryService growth;
    private final PlatformConfigService platformConfig;
    private final GiftCategoryRepository categories;
    private final GiftOrderRepository orders;
    private final GrowthLedgerService ledger;
    private final AccountQueryService accounts;

    public GiftCatalogService(GiftCatalogRepository catalog, GiftRecipientGuard recipients, GrowthQueryService growth,
            PlatformConfigService platformConfig, GiftCategoryRepository categories, GiftOrderRepository orders,
            GrowthLedgerService ledger, AccountQueryService accounts) {
        this.catalog = catalog;
        this.recipients = recipients;
        this.growth = growth;
        this.platformConfig = platformConfig;
        this.categories = categories;
        this.orders = orders;
        this.ledger = ledger;
        this.accounts = accounts;
    }

    @Transactional(readOnly = true)
    public GiftCatalogResponse catalog(long senderId, Long petId, Long postId) {
        if ((petId == null) == (postId == null)) {
            throw AppException.validation("petId 与 postId 必须且只能给一个");
        }
        GiftPet pet = petId != null
                ? recipients.requireRecipient(senderId, petId)
                : recipients.requireRecipientOfPost(senderId, postId);
        PetType species = parsePetType(pet.petType());
        GrowthLevel lv = growth.getLevel(senderId);
        boolean participating = lv != null && lv.participating();
        int level = participating ? lv.level() : 0;

        List<GiftCatalogItem> online = new ArrayList<>(catalog.findByStatus(GiftCatalogStatus.ONLINE));
        online.removeIf(g -> !g.isOnline());
        online.sort(orderFor(species));

        Map<Long, ReceivedFrom> received = receivedFrom(pet.petId());
        Map<Long, Integer> expByPrice = new HashMap<>();
        Map<Long, GiftCategory> onlineCategories = new LinkedHashMap<>();
        for (GiftCategory c : categories.findAllByStatusOrderBySortOrderAscIdAsc(GiftCategoryStatus.ONLINE)) {
            onlineCategories.put(c.getId(), c);
        }
        Map<Long, List<GiftItem>> byCategory = new HashMap<>();
        List<Long> uncategorized = new ArrayList<>();

        Map<Long, List<GiftItem>> tiers = new TreeMap<>();
        Map<Integer, List<GiftItem>> exclusive = new TreeMap<>();
        Integer requiredLevel = null;
        for (GiftCatalogItem g : online) {
            if (g.getKind() == GiftKind.NORMAL) {
                GiftItem it = item(g, senderExp(participating, g.getPrice(), expByPrice), received.get(g.getId()));
                tiers.computeIfAbsent(g.getPrice(), k -> new ArrayList<>()).add(it);
                Long cid = g.getCategoryId();
                if (cid != null && onlineCategories.containsKey(cid)) {
                    byCategory.computeIfAbsent(cid, k -> new ArrayList<>()).add(it);
                } else {
                    uncategorized.add(g.getId());
                }
            } else if (g.getMinLevel() != null && g.getMinLevel() <= level) {
                exclusive.computeIfAbsent(g.getMinLevel(), k -> new ArrayList<>())
                        .add(item(g, senderExp(participating, g.getPrice(), expByPrice), received.get(g.getId())));
            } else if (g.getMinLevel() != null && (requiredLevel == null || g.getMinLevel() < requiredLevel)) {
                requiredLevel = g.getMinLevel();
            }
        }
        if (!uncategorized.isEmpty()) {
            // 一次请求一行（code review #3：逐件 WARN 会刷屏）；只记礼物 id。
            log.warn("gift catalog items without online category count={} giftIds={}", uncategorized.size(),
                    uncategorized);
        }
        List<Category> categoryGroups = new ArrayList<>();
        for (GiftCategory c : onlineCategories.values()) {
            List<GiftItem> gifts = byCategory.get(c.getId());
            if (gifts != null && !gifts.isEmpty()) {
                categoryGroups.add(new Category(c.getId(), c.getNameEn(), c.getNameId(), List.copyOf(gifts)));
            }
        }
        return new GiftCatalogResponse(
                new Recipient(pet.petId(), pet.name(), pet.avatarUrl(), pet.petType(), pet.breed()),
                platformConfig.pricing().getGiftQrisMinAmount(),
                tiers.entrySet().stream().map(e -> new Tier(e.getKey(), List.copyOf(e.getValue()))).toList(),
                exclusive.entrySet().stream().map(e -> new ExclusiveGroup(e.getKey(), List.copyOf(e.getValue())))
                        .toList(),
                requiredLevel == null ? null : new LockBlock(requiredLevel),
                List.copyOf(categoryGroups));
    }

    /** 送 1 个时送礼人可得 EXP（D-3 / D-7）：不参与成长体系 = 0；同价只算一次。 */
    private int senderExp(boolean participating, long price, Map<Long, Integer> cache) {
        if (!participating) {
            return 0;
        }
        return cache.computeIfAbsent(price, p -> ledger.pointsForAmount(RuleCode.GIFT_SENT, p));
    }

    /**
     * 这只宠物每件礼物的送礼人（一条聚合 SQL + 一次批量昵称）：按送礼人最近送达时刻倒序取前 3，其余计 {@code more}。
     */
    private Map<Long, ReceivedFrom> receivedFrom(long petId) {
        record Row(long giftId, long senderId, Instant lastAt) { }
        List<Row> rows = new ArrayList<>();
        for (Object[] r : orders.deliveredSendersOfPet(petId)) {
            rows.add(new Row(((Number) r[0]).longValue(), ((Number) r[1]).longValue(), toInstant(r[2])));
        }
        if (rows.isEmpty()) {
            return Map.of();
        }
        Map<Long, List<Row>> byGift = new HashMap<>();
        for (Row r : rows) {
            byGift.computeIfAbsent(r.giftId(), k -> new ArrayList<>()).add(r);
        }
        Comparator<Row> recentFirst = Comparator.comparing(Row::lastAt, Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(Row::senderId, Comparator.reverseOrder());
        Set<Long> shownSenders = new HashSet<>();
        for (List<Row> list : byGift.values()) {
            list.sort(recentFirst);
            list.stream().limit(RECEIVED_FROM_LIMIT).forEach(r -> shownSenders.add(r.senderId()));
        }
        Map<Long, AuthorView> views = accounts.findAuthorViewsWithoutTags(shownSenders);
        Map<Long, ReceivedFrom> out = new HashMap<>();
        for (Map.Entry<Long, List<Row>> e : byGift.entrySet()) {
            List<Row> list = e.getValue();
            List<Sender> senders = list.stream().limit(RECEIVED_FROM_LIMIT).map(r -> {
                AuthorView v = views.get(r.senderId());
                boolean deleted = v == null || v.deleted();
                return new Sender(deleted ? null : v.nickname(), deleted);
            }).toList();
            out.put(e.getKey(), new ReceivedFrom(senders, Math.max(0, list.size() - RECEIVED_FROM_LIMIT)));
        }
        return out;
    }

    private static Instant toInstant(Object o) {
        if (o == null) {
            return null;
        }
        if (o instanceof Instant i) {
            return i;
        }
        if (o instanceof java.sql.Timestamp t) {
            return t.toInstant();
        }
        if (o instanceof java.time.OffsetDateTime odt) {
            return odt.toInstant();
        }
        throw new IllegalStateException("unexpected timestamp type " + o.getClass());
    }

    /** 组内排序（AD-10 / PRD AC3a）：适用该物种 0 → 通用 1 → 其他 2，再 {@code sort_order}、{@code id}。 */
    static Comparator<GiftCatalogItem> orderFor(PetType species) {
        return Comparator.<GiftCatalogItem>comparingInt(g -> g.suits(species) ? 0 : g.isUniversal() ? 1 : 2)
                .thenComparingInt(GiftCatalogItem::getSortOrder)
                .thenComparing(GiftCatalogItem::getId, Comparator.nullsLast(Comparator.naturalOrder()));
    }

    private static PetType parsePetType(String raw) {
        if (raw == null) {
            return null;
        }
        try {
            return PetType.valueOf(raw);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static GiftItem item(GiftCatalogItem g, int senderExp, ReceivedFrom receivedFrom) {
        return new GiftItem(g.getPublicToken(), g.getNameEn(), g.getNameId(), g.getIconUrl(), g.getPrice(),
                g.getKind().name(), g.getMinLevel(), senderExp, receivedFrom);
    }
}
