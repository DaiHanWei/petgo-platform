package com.tailtopia.gift.service;

import com.tailtopia.config.service.PlatformConfigService;
import com.tailtopia.gift.domain.GiftCatalogItem;
import com.tailtopia.gift.domain.GiftCatalogStatus;
import com.tailtopia.gift.domain.GiftKind;
import com.tailtopia.gift.dto.GiftCatalogResponse;
import com.tailtopia.gift.dto.GiftCatalogResponse.ExclusiveGroup;
import com.tailtopia.gift.dto.GiftCatalogResponse.GiftItem;
import com.tailtopia.gift.dto.GiftCatalogResponse.LockBlock;
import com.tailtopia.gift.dto.GiftCatalogResponse.Recipient;
import com.tailtopia.gift.dto.GiftCatalogResponse.Tier;
import com.tailtopia.gift.port.GiftPetDirectory.GiftPet;
import com.tailtopia.gift.repository.GiftCatalogRepository;
import com.tailtopia.growth.domain.GrowthLevel;
import com.tailtopia.growth.service.GrowthQueryService;
import com.tailtopia.profile.domain.PetType;
import com.tailtopia.shared.error.AppException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * App 礼物目录（V1.4.0 gift Story 1.1 · AC4 · AD-10 · PRD AC3 / AC3a）。
 *
 * <ul>
 *   <li>入参 {@code petId} / {@code postId} 二选一（{@code postId} 经 {@code GiftPetDirectory.petsOfPost} 取第一只，
 *       单宠过渡；对 AD-10 的补充）；收礼宠物校验与发起同口径（{@link GiftRecipientGuard}）。</li>
 *   <li>组内排序：适用收礼宠物物种 → 通用（三个物种标记皆 false）→ 其他；同组 {@code sort_order ASC, id ASC}。</li>
 *   <li>专属礼物按 {@link GrowthQueryService#getLevel} 过滤（{@code participating = false} 视为 level 0），
 *       只放 {@code min_level <= level}；{@code lockBlock.requiredLevel} = 未解锁专属礼物里最小的 {@code min_level}。</li>
 * </ul>
 */
@Service
public class GiftCatalogService {

    private final GiftCatalogRepository catalog;
    private final GiftRecipientGuard recipients;
    private final GrowthQueryService growth;
    private final PlatformConfigService platformConfig;

    public GiftCatalogService(GiftCatalogRepository catalog, GiftRecipientGuard recipients, GrowthQueryService growth,
            PlatformConfigService platformConfig) {
        this.catalog = catalog;
        this.recipients = recipients;
        this.growth = growth;
        this.platformConfig = platformConfig;
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
        int level = levelOf(senderId);

        List<GiftCatalogItem> online = new ArrayList<>(catalog.findByStatus(GiftCatalogStatus.ONLINE));
        online.removeIf(g -> !g.isOnline());
        online.sort(orderFor(species));

        Map<Long, List<GiftItem>> tiers = new TreeMap<>();
        Map<Integer, List<GiftItem>> exclusive = new TreeMap<>();
        Integer requiredLevel = null;
        for (GiftCatalogItem g : online) {
            if (g.getKind() == GiftKind.NORMAL) {
                tiers.computeIfAbsent(g.getPrice(), k -> new ArrayList<>()).add(item(g));
            } else if (g.getMinLevel() != null && g.getMinLevel() <= level) {
                exclusive.computeIfAbsent(g.getMinLevel(), k -> new ArrayList<>()).add(item(g));
            } else if (g.getMinLevel() != null && (requiredLevel == null || g.getMinLevel() < requiredLevel)) {
                requiredLevel = g.getMinLevel();
            }
        }
        return new GiftCatalogResponse(
                new Recipient(pet.petId(), pet.name(), pet.avatarUrl(), pet.petType(), pet.breed()),
                platformConfig.pricing().getGiftQrisMinAmount(),
                tiers.entrySet().stream().map(e -> new Tier(e.getKey(), List.copyOf(e.getValue()))).toList(),
                exclusive.entrySet().stream().map(e -> new ExclusiveGroup(e.getKey(), List.copyOf(e.getValue())))
                        .toList(),
                requiredLevel == null ? null : new LockBlock(requiredLevel));
    }

    /** 送礼人用于权益判断的等级；不参与成长体系（虚拟号 / 查无此人）按 0 —— 不给任何专属礼物。 */
    private int levelOf(long senderId) {
        GrowthLevel lv = growth.getLevel(senderId);
        return lv != null && lv.participating() ? lv.level() : 0;
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

    private static GiftItem item(GiftCatalogItem g) {
        return new GiftItem(g.getPublicToken(), g.getNameEn(), g.getNameId(), g.getIconUrl(), g.getPrice(),
                g.getKind().name(), g.getMinLevel());
    }
}
