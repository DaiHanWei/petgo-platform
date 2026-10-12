package com.tailtopia.ops.popup.service;

import com.tailtopia.auth.domain.User;
import com.tailtopia.auth.service.AccountQueryService;
import com.tailtopia.ops.popup.domain.OpsPopup;
import com.tailtopia.ops.popup.domain.OpsPopupAudience;
import com.tailtopia.ops.popup.domain.OpsPopupFrequency;
import com.tailtopia.ops.popup.dto.OpsPopupCandidateView;
import com.tailtopia.ops.popup.repository.OpsPopupImpressionRepository;
import com.tailtopia.ops.popup.repository.OpsPopupRepository;
import com.tailtopia.ops.target.OpsTargetPolicy;
import com.tailtopia.ops.target.OpsTargetType;
import com.tailtopia.ops.target.OpsTargetView;
import com.tailtopia.shared.schedule.ScheduleWindow;
import com.tailtopia.shop.service.ShopImageUrlResolver;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 运营弹窗候选（V1.3.4 Story 5.1 · AC3 · AD-14 / AD-15）。
 *
 * <p>返回<b>过滤后的有序列表</b>（最多 {@value #MAX_ITEMS} 条）而不是「一个」：App 还要用本地频次记录二次剔除
 * （游客期间弹过的 ONCE 登录后不再弹，PRD AC4），取第一个可用的。
 *
 * <p>过滤：ACTIVE + 时间窗 {@code [starts_at, ends_at)} + 人群 + 跳转目标可用 + ID 图可解析
 * + （仅登录用户）频次未满。排序 {@code priority DESC, created_at DESC, id DESC}（仓储层）。
 *
 * <p>🔴 {@code viewerUserId} 只能是 {@code role=USER} 的 users.id；游客 / VET 由 controller 传 null。
 * 游客不做频次过滤（频次在设备本地，AD-15），也<b>不碰曝光表</b>。
 */
@Service
public class OpsPopupCandidateService {

    static final int MAX_ITEMS = 20;

    private static final Set<OpsPopupAudience> GUEST_AUDIENCES = EnumSet.of(OpsPopupAudience.ALL);
    private static final Set<OpsPopupAudience> USER_AUDIENCES = EnumSet.allOf(OpsPopupAudience.class);

    private final OpsPopupRepository popups;
    private final OpsPopupImpressionRepository impressions;
    private final OpsTargetPolicy targetPolicy;
    private final ShopImageUrlResolver imageUrls;
    private final AccountQueryService accounts;
    private final Clock clock;

    @Autowired
    public OpsPopupCandidateService(OpsPopupRepository popups, OpsPopupImpressionRepository impressions,
            OpsTargetPolicy targetPolicy, ShopImageUrlResolver imageUrls, AccountQueryService accounts) {
        this(popups, impressions, targetPolicy, imageUrls, accounts, Clock.systemUTC());
    }

    OpsPopupCandidateService(OpsPopupRepository popups, OpsPopupImpressionRepository impressions,
            OpsTargetPolicy targetPolicy, ShopImageUrlResolver imageUrls, AccountQueryService accounts,
            Clock clock) {
        this.popups = popups;
        this.impressions = impressions;
        this.targetPolicy = targetPolicy;
        this.imageUrls = imageUrls;
        this.accounts = accounts;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<OpsPopupCandidateView> candidates(Long viewerUserId) {
        Instant now = clock.instant();
        // 带了 USER token 但查不到普通用户行 → 按游客处理（拿不到注册时间就无从判新老）。
        // 带了 USER token 但查不到普通用户行 / 已注销 → 按游客处理（拿不到注册时间就无从判新老）。
        User viewer = viewerUserId == null
                ? null
                : accounts.findUserById(viewerUserId).filter(u -> u.getDeletedAt() == null).orElse(null);

        List<OpsPopup> live = popups.findLiveCandidates(now, viewer == null ? GUEST_AUDIENCES : USER_AUDIENCES);

        // ① 人群 → ② 频次（便宜的过滤先做，缩小后面的商品查询范围）→ ③ 跳转目标 → ④ ID 图。
        List<OpsPopup> eligible = live;
        if (viewer != null) {
            eligible = live.stream().filter(p -> audienceMatches(p, viewer.getCreatedAt(), now)).toList();
            if (!eligible.isEmpty()) {
                eligible = dropExhausted(eligible, viewer.getId(), now);
            }
        }

        // 商品在售集合一次查完（避免逐条查实体的 N+1）；判定口径仍是 OpsTargetPolicy 那一份。
        List<String> productTokens = eligible.stream()
                .filter(p -> p.getTargetType() == OpsTargetType.PRODUCT && p.getTargetValue() != null)
                .map(OpsPopup::getTargetValue)
                .toList();
        Set<String> onSale = productTokens.isEmpty() ? Set.of() : targetPolicy.availableProductTokens(productTokens);

        List<Resolved> passed = new ArrayList<>();
        for (OpsPopup p : eligible) {
            if (!OpsTargetPolicy.isAvailable(p.getTargetType(), p.getTargetValue(), onSale)) {
                continue;
            }
            String imageId = resolveImage(p.getImageUrlId());
            if (imageId == null) {
                continue;
            }
            // EN 图不可用 → 字段省略，App 回落 ID 图（PRD AC6），不影响该弹窗下发。
            passed.add(new Resolved(p, imageId, resolveImage(p.getImageUrlEn())));
        }

        return passed.stream().limit(MAX_ITEMS).map(OpsPopupCandidateService::toView).toList();
    }

    /** NEW：{@code created_at >= now − N×24h}（恰好 N×24h 算 NEW）；OLD：注册时长 &gt; N×24h。 */
    static boolean audienceMatches(OpsPopup p, Instant registeredAt, Instant now) {
        return switch (p.getAudience()) {
            case ALL -> true;
            case NEW -> registeredAt != null && isNewUser(registeredAt, p.getNewUserDays(), now);
            case OLD -> registeredAt != null && !isNewUser(registeredAt, p.getNewUserDays(), now);
        };
    }

    private static boolean isNewUser(Instant registeredAt, int newUserDays, Instant now) {
        Instant boundary = now.minus(Duration.ofHours(24L * newUserDays));
        return !registeredAt.isBefore(boundary);
    }

    /** ONCE：从无曝光；DAILY：当前 WIB 自然日 {@code [今日 00:00 WIB, 明日 00:00 WIB)} 无曝光。 */
    private List<OpsPopup> dropExhausted(List<OpsPopup> candidates, long userId, Instant now) {
        List<Long> onceIds = new ArrayList<>();
        List<Long> dailyIds = new ArrayList<>();
        for (OpsPopup p : candidates) {
            (p.getFrequency() == OpsPopupFrequency.DAILY ? dailyIds : onceIds).add(p.getId());
        }
        Set<Long> onceShown = onceIds.isEmpty() ? Set.of() : impressions.findShownPopupIds(userId, onceIds);
        Set<Long> dailyShown = dailyIds.isEmpty()
                ? Set.of()
                : impressions.findShownPopupIds(userId, dailyIds, startOfWibDay(now));
        return candidates.stream()
                .filter(p -> !onceShown.contains(p.getId()) && !dailyShown.contains(p.getId()))
                .toList();
    }

    static Instant startOfWibDay(Instant now) {
        return now.atZone(ScheduleWindow.WIB).toLocalDate().atStartOfDay(ScheduleWindow.WIB).toInstant();
    }

    /**
     * 图片地址解析（AC3.4）：{@code https://} 且可解析出 host 才原样返回（复用 {@link OpsTargetPolicy#isHttpsUrl}）；
     * {@code http://} 不可用（不下发明文图）；
     * 其余视为公开桶 objectKey，经 {@link ShopImageUrlResolver#publicUrl} 拼 CDN（未配置 → null = 不可用）。
     */
    String resolveImage(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        if (raw.regionMatches(true, 0, "https://", 0, 8)) {
            return OpsTargetPolicy.isHttpsUrl(raw) ? raw : null;
        }
        if (raw.regionMatches(true, 0, "http://", 0, 7)) {
            return null;
        }
        return imageUrls.publicUrl(raw);
    }

    private static OpsPopupCandidateView toView(Resolved r) {
        OpsPopup p = r.popup();
        return new OpsPopupCandidateView(
                p.getPublicToken(),
                p.getFrequency().name(),
                r.imageId(),
                r.imageEn(),
                blankToNull(p.getTitleId()),
                blankToNull(p.getTitleEn()),
                blankToNull(p.getBodyId()),
                blankToNull(p.getBodyEn()),
                p.getButtonTextId(),
                blankToNull(p.getButtonTextEn()),
                OpsTargetView.of(p.getTargetType(), p.getTargetValue()));
    }

    private static String blankToNull(String s) {
        return s == null || s.trim().isEmpty() ? null : s;
    }

    private record Resolved(OpsPopup popup, String imageId, String imageEn) {
    }
}
