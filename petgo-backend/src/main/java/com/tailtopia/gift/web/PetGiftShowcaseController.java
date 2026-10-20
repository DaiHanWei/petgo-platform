package com.tailtopia.gift.web;

import com.tailtopia.auth.dto.AuthorView;
import com.tailtopia.auth.service.AccountQueryService;
import com.tailtopia.gift.port.GiftPetDirectory.GiftPet;
import com.tailtopia.gift.service.GiftShowcaseAccess;
import com.tailtopia.gift.service.GiftShowcaseQuery;
import com.tailtopia.gift.service.GiftShowcaseQuery.SenderGiftKind;
import com.tailtopia.gift.service.GiftShowcaseQuery.SupporterKey;
import com.tailtopia.gift.service.GiftShowcaseQuery.SupporterRow;
import com.tailtopia.gift.web.dto.GiftSupporterPage;
import com.tailtopia.gift.web.dto.GiftSupporterRow;
import com.tailtopia.gift.web.dto.GiftWallItemView;
import com.tailtopia.gift.web.dto.PetGiftShowcaseResponse;
import com.tailtopia.gift.web.dto.SupporterCursor;
import com.tailtopia.shared.error.AppException;
import com.tailtopia.shared.error.ErrorTypes;
import com.tailtopia.shared.media.AliyunOssClient;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 礼物页（V1.4.0 gift Story 2.1 · AC1 / AC2 / AC3 · AD-10）。两条都只给 {@code role=USER}（{@code SecurityConfig} 精确
 * matcher）：主人视角判定靠 {@code viewerId == ownerId}，兽医 token 的 {@code sub} 与 {@code users.id} 撞号会被误判成主人。
 * <ul>
 *   <li>{@code GET /api/v1/pets/{petId}/gifts} —— 汇总 + 礼物墙；</li>
 *   <li>{@code GET /api/v1/pets/{petId}/gifts/supporters?cursor=} —— 粉丝榜（每页 20）。</li>
 * </ul>
 * 与 1.1 的 {@code POST /api/v1/pets/{petId}/gifts}（{@link GiftController}）同路径不同方法，不冲突。
 *
 * <p>🔴 查看者只取 JWT {@code sub}，不接受任何 userId / token 参数（AC3.3）。可见性统一经 {@link GiftShowcaseAccess}。
 * 不下发 {@code sender_id} / {@code owner_id} / 订单或目录 token（AC8.3）；本类不打日志（不记昵称与金额，AC8.2）。
 */
@RestController
public class PetGiftShowcaseController {

    /** 粉丝榜每页条数（AC2.1）。 */
    static final int PAGE_SIZE = 20;
    /** 粉丝行展示的礼物种类数（AC2.2）。 */
    static final int ROW_GIFT_KINDS = 3;

    private final GiftShowcaseAccess access;
    private final GiftShowcaseQuery query;
    private final AccountQueryService accounts;

    public PetGiftShowcaseController(GiftShowcaseAccess access, GiftShowcaseQuery query,
            AccountQueryService accounts) {
        this.access = access;
        this.query = query;
        this.accounts = accounts;
    }

    @GetMapping("/api/v1/pets/{petId}/gifts")
    public PetGiftShowcaseResponse showcase(@AuthenticationPrincipal Jwt jwt, @PathVariable long petId) {
        GiftShowcaseAccess.Visible v = access.requireVisible(currentUserId(jwt), petId);
        GiftPet pet = v.pet();
        GiftShowcaseQuery.PetGiftSummary summary = query.summariesOf(List.of(petId))
                .getOrDefault(petId, GiftShowcaseQuery.PetGiftSummary.EMPTY);
        // 墙数量与 giftCount 不排除任何人（AC12a）；只有 supporterCount 在主人视角排除其隐藏的送礼人（AC2.5）。
        long supporterCount = query.supporterCount(petId, v.viewerIsOwner() ? pet.ownerId() : null);
        List<GiftWallItemView> wall = query.wall(petId).stream().map(GiftWallItemView::of).toList();
        return new PetGiftShowcaseResponse(
                new PetGiftShowcaseResponse.PetView(pet.petId(), pet.name(),
                        pet.avatarUrl() == null ? null : AliyunOssClient.exifStrippedDeliveryUrl(pet.avatarUrl()),
                        pet.petType()),
                v.viewerIsOwner(), summary.totalValue(), summary.giftCount(), supporterCount, wall);
    }

    @GetMapping("/api/v1/pets/{petId}/gifts/supporters")
    public GiftSupporterPage supporters(@AuthenticationPrincipal Jwt jwt, @PathVariable long petId,
            @RequestParam(name = "cursor", required = false) String cursor) {
        GiftShowcaseAccess.Visible v = access.requireVisible(currentUserId(jwt), petId);
        SupporterCursor after = null;
        if (cursor != null && !cursor.isBlank()) {
            after = SupporterCursor.decodeOrNull(cursor);
            if (after == null) {
                throw new AppException(HttpStatus.BAD_REQUEST, ErrorTypes.VALIDATION, "游标无效");
            }
        }
        Long hiddenFor = v.viewerIsOwner() ? v.pet().ownerId() : null;
        SupporterKey key = after == null ? null
                : new SupporterKey(after.totalAmount(), after.lastSentAt(), after.senderId());
        List<SupporterRow> fetched = query.supporters(petId, hiddenFor, key, PAGE_SIZE + 1);
        boolean hasMore = fetched.size() > PAGE_SIZE;
        List<SupporterRow> page = hasMore ? fetched.subList(0, PAGE_SIZE) : fetched;
        if (page.isEmpty()) {
            return new GiftSupporterPage(List.of(), null);
        }

        List<Long> senderIds = page.stream().map(SupporterRow::senderId).toList();
        Map<Long, AuthorView> people = accounts.findAuthorViewsWithoutTags(senderIds);
        Map<Long, List<SenderGiftKind>> kinds = query.giftKindsBySender(petId, senderIds);

        int rank = after == null ? 1 : after.nextRank();
        List<GiftSupporterRow> items = new ArrayList<>(page.size());
        for (SupporterRow r : page) {
            AuthorView who = people.getOrDefault(r.senderId(), AuthorView.anonymized(r.senderId()));
            List<SenderGiftKind> all = kinds.getOrDefault(r.senderId(), List.of());
            List<GiftSupporterRow.GiftKind> top = all.stream().limit(ROW_GIFT_KINDS)
                    .map(k -> new GiftSupporterRow.GiftKind(k.iconUrl(), k.nameEn(), k.nameId(), k.count()))
                    .toList();
            items.add(new GiftSupporterRow(rank++, who.deleted() ? null : who.nickname(),
                    who.deleted() ? null : who.avatarUrl(), who.deleted(), r.totalAmount(), r.giftCount(), top,
                    Math.max(0, all.size() - ROW_GIFT_KINDS)));
        }
        String next = null;
        if (hasMore) {
            SupporterRow last = page.get(page.size() - 1);
            next = new SupporterCursor(last.totalAmount(), last.lastSentAt(), last.senderId(), rank).encode();
        }
        return new GiftSupporterPage(items, next);
    }

    private static long currentUserId(Jwt jwt) {
        if (jwt == null || jwt.getSubject() == null) {
            throw AppException.unauthorized("需要登录后访问");
        }
        try {
            return Long.parseLong(jwt.getSubject());
        } catch (NumberFormatException e) {
            throw AppException.unauthorized("无效的登录凭证");
        }
    }
}
