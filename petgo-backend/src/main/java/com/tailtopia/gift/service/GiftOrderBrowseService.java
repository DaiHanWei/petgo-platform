package com.tailtopia.gift.service;

import com.tailtopia.auth.dto.AuthorView;
import com.tailtopia.auth.service.AccountQueryService;
import com.tailtopia.gift.domain.GiftCategoryStatus;
import com.tailtopia.gift.port.GiftPetDirectory;
import com.tailtopia.gift.port.GiftPetDirectory.GiftPet;
import com.tailtopia.gift.repository.GiftCategoryRepository;
import com.tailtopia.gift.web.dto.GiftOpenRequest;
import com.tailtopia.gift.web.dto.GiftOpenResponse;
import com.tailtopia.gift.web.dto.GiftOrderCard;
import com.tailtopia.gift.web.dto.GiftOrderCursor;
import com.tailtopia.gift.web.dto.GiftOrderDetail;
import com.tailtopia.gift.web.dto.GiftOrderPreview;
import com.tailtopia.gift.web.dto.PetGiftOrderPage;
import com.tailtopia.gift.web.dto.PetGiftShowcaseResponse;
import com.tailtopia.growth.domain.GrowthLevel;
import com.tailtopia.growth.domain.RuleCode;
import com.tailtopia.growth.service.GrowthLedgerService;
import com.tailtopia.growth.service.GrowthQueryService;
import com.tailtopia.shared.error.AppException;
import com.tailtopia.shared.error.ErrorTypes;
import com.tailtopia.shared.media.AliyunOssClient;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 逐笔礼物（V1.5.0 gift-pack Story 1-4 · D-7 / D-9 ~ D-12）：列表、Diary 礼物行、详情、拆礼物。
 *
 * <p>可见性一律经 {@link GiftShowcaseAccess}（与旧礼物页同一判定）。主人判定只靠 {@code viewerId == ownerId}。
 * EXP 只在这里按面值现算用于展示（不入账）；拆礼物的写入在 {@link GiftOpenService}（不碰成长值 / 钱包 / 通知）。
 * 本类不打日志（不记昵称、祝福语、宠物名）。
 */
@Service
public class GiftOrderBrowseService {

    public static final int PAGE_SIZE = 30;
    public static final int PREVIEW_SIZE = 10;
    public static final int SENDER_PET_NAMES = 3;

    public enum Filter { ALL, UNOPENED }

    private final GiftShowcaseAccess access;
    private final GiftOrderQuery query;
    private final GiftOpenService opener;
    private final GiftShowcaseQuery showcase;
    private final GiftCategoryRepository categories;
    private final AccountQueryService accounts;
    private final GiftPetDirectory pets;
    private final GrowthQueryService growth;
    private final GrowthLedgerService ledger;

    public GiftOrderBrowseService(GiftShowcaseAccess access, GiftOrderQuery query, GiftOpenService opener,
            GiftShowcaseQuery showcase, GiftCategoryRepository categories, AccountQueryService accounts,
            GiftPetDirectory pets, GrowthQueryService growth, GrowthLedgerService ledger) {
        this.access = access;
        this.query = query;
        this.opener = opener;
        this.showcase = showcase;
        this.categories = categories;
        this.accounts = accounts;
        this.pets = pets;
        this.growth = growth;
        this.ledger = ledger;
    }

    // ——————————————————— 列表 / 礼物行 ———————————————————

    @Transactional(readOnly = true)
    public PetGiftOrderPage list(long viewerId, long petId, Filter filter, Long categoryId, String cursor) {
        GiftShowcaseAccess.Visible v = access.requireVisible(viewerId, petId);
        GiftOrderCursor after = null;
        if (cursor != null && !cursor.isBlank()) {
            after = GiftOrderCursor.decodeOrNull(cursor);
            if (after == null) {
                throw new AppException(HttpStatus.BAD_REQUEST, ErrorTypes.VALIDATION, "游标无效");
            }
        }
        boolean owner = v.viewerIsOwner();
        boolean unopenedOnly = owner && filter == Filter.UNOPENED;
        List<GiftOrderRow> fetched = query.page(petId, owner, unopenedOnly, categoryId, after, PAGE_SIZE + 1);
        boolean hasMore = fetched.size() > PAGE_SIZE;
        List<GiftOrderRow> rows = hasMore ? fetched.subList(0, PAGE_SIZE) : fetched;
        List<GiftOrderCard> items = cards(rows, owner);
        String next = null;
        if (hasMore) {
            GiftOrderRow last = rows.get(rows.size() - 1);
            next = new GiftOrderCursor(owner && !last.opened() ? 1 : 0, last.deliveredAt(), last.id()).encode();
        }
        Integer unopened = owner ? query.unopenedCount(petId) : null;
        if (after != null) {
            return new PetGiftOrderPage(items, next, unopened, null, null, null);
        }
        List<PetGiftOrderPage.CategoryChip> chips = categories
                .findAllByStatusOrderBySortOrderAscIdAsc(GiftCategoryStatus.ONLINE).stream()
                .map(c -> new PetGiftOrderPage.CategoryChip(c.getId(), c.getNameEn(), c.getNameId())).toList();
        return new PetGiftOrderPage(items, next, unopened, chips, petView(v.pet()), owner);
    }

    @Transactional(readOnly = true)
    public GiftOrderPreview preview(long viewerId, long petId) {
        GiftShowcaseAccess.Visible v = access.requireVisible(viewerId, petId);
        boolean owner = v.viewerIsOwner();
        long giftCount = showcase.summariesOf(List.of(petId))
                .getOrDefault(petId, GiftShowcaseQuery.PetGiftSummary.EMPTY).giftCount();
        List<GiftOrderRow> rows = query.page(petId, owner, false, null, null, PREVIEW_SIZE);
        return new GiftOrderPreview(giftCount, owner ? query.unopenedCount(petId) : null, cards(rows, owner));
    }

    // ——————————————————— 详情 ———————————————————

    /** 订单不存在 / 非已送达 / 宠物已删 → 404；对宠物不可见 → 同礼物页。访客也能看未拆的（含祝福语）。 */
    @Transactional(readOnly = true)
    public GiftOrderDetail detail(long viewerId, String orderToken) {
        GiftOrderRow r = query.deliveredByToken(orderToken)
                .filter(o -> o.petId() != null)
                .orElseThrow(() -> AppException.notFound("礼物不存在"));
        GiftShowcaseAccess.Visible v = access.requireVisible(viewerId, r.petId());
        return details(List.of(r), v, viewerId, null).get(0);
    }

    // ——————————————————— 拆礼物 ———————————————————

    /** 仅主人（否则 403）；body 二选一，超 50 / 都没有 / 都有 → 422。不入账（D-7）。 */
    @Transactional
    public GiftOpenResponse open(long viewerId, long petId, GiftOpenRequest req) {
        GiftShowcaseAccess.Visible v = access.requireVisible(viewerId, petId);
        if (!v.viewerIsOwner()) {
            throw AppException.forbidden("只有主人可以拆礼物");
        }
        boolean all = req != null && Boolean.TRUE.equals(req.all());
        List<String> tokens = req == null || req.orderTokens() == null ? List.of() : req.orderTokens();
        if (all == !tokens.isEmpty()) {
            throw AppException.validation("orderTokens 与 all 须且只能给一个");
        }
        Set<String> distinct = new LinkedHashSet<>();
        for (String t : tokens) {
            if (t == null || t.isBlank()) {
                throw AppException.validation("orderTokens 含空值");
            }
            distinct.add(t);
        }
        if (distinct.size() > GiftOpenService.OPEN_TOKENS_MAX) {
            throw AppException.validation("一次最多拆 50 份");
        }
        GiftOpenService.Result res = all ? opener.openAll(petId) : opener.openTokens(petId, distinct);
        List<GiftOrderRow> rows = query.byIds(res.involvedIds());
        Map<Long, Boolean> already = new HashMap<>();
        for (GiftOrderRow r : rows) {
            already.put(r.id(), !res.openedNow().contains(r.id()));
        }
        List<GiftOrderDetail> items = details(rows, v, viewerId, already);
        int total = items.stream().mapToInt(d -> d.ownerExp() == null ? 0 : d.ownerExp()).sum();
        return new GiftOpenResponse(items, total);
    }

    // ——————————————————— 组装 ———————————————————

    private List<GiftOrderCard> cards(List<GiftOrderRow> rows, boolean owner) {
        if (rows.isEmpty()) {
            return List.of();
        }
        Map<Long, AuthorView> people = accounts.findAuthorViewsWithoutTags(
                rows.stream().map(GiftOrderRow::senderId).distinct().toList());
        List<GiftOrderCard> out = new ArrayList<>(rows.size());
        for (GiftOrderRow r : rows) {
            out.add(GiftOrderDetailAssembler.card(r, people.get(r.senderId()), owner));
        }
        return out;
    }

    private List<GiftOrderDetail> details(List<GiftOrderRow> rows, GiftShowcaseAccess.Visible v, long viewerId,
            Map<Long, Boolean> alreadyOpened) {
        if (rows.isEmpty()) {
            return List.of();
        }
        boolean owner = v.viewerIsOwner();
        Map<Long, AuthorView> people = accounts.findAuthorViewsWithoutTags(
                rows.stream().map(GiftOrderRow::senderId).distinct().toList());
        // 一次批量取全部（未注销）送礼人的宠物名（code review #3：open-all 最多 200 笔，不得逐人查）
        List<Long> activeSenders = rows.stream().map(GiftOrderRow::senderId).distinct()
                .filter(id -> people.get(id) != null && !people.get(id).deleted()).toList();
        Map<Long, List<String>> petNames = activeSenders.isEmpty() ? Map.of()
                : pets.petNamesOfOwners(activeSenders, SENDER_PET_NAMES);
        boolean ownerParticipating = false;
        if (owner) {
            GrowthLevel lv = growth.getLevel(v.pet().ownerId());
            ownerParticipating = lv != null && lv.participating();
        }
        Map<Long, Integer> expByFace = new HashMap<>();
        List<GiftOrderDetail> out = new ArrayList<>(rows.size());
        for (GiftOrderRow r : rows) {
            AuthorView who = people.get(r.senderId());
            List<String> names = who == null || who.deleted() ? List.of()
                    : petNames.getOrDefault(r.senderId(), List.of());
            Integer exp = null;
            if (owner) {
                boolean p = ownerParticipating;
                exp = p ? expByFace.computeIfAbsent(r.faceValue(),
                        f -> ledger.pointsForAmount(RuleCode.GIFT_RECEIVED, f)) : 0;
            }
            out.add(GiftOrderDetailAssembler.detail(r, v.pet().petId(), v.pet().name(), viewerId, owner, who, names,
                    exp, alreadyOpened == null ? null : alreadyOpened.get(r.id())));
        }
        return out;
    }

    /** 宠物头：<b>复用</b> {@link PetGiftShowcaseResponse.PetView}，组装照 {@code PetGiftShowcaseController.showcase}。 */
    static PetGiftShowcaseResponse.PetView petView(GiftPet pet) {
        return new PetGiftShowcaseResponse.PetView(pet.petId(), pet.name(),
                pet.avatarUrl() == null ? null : AliyunOssClient.exifStrippedDeliveryUrl(pet.avatarUrl()),
                pet.petType());
    }
}
