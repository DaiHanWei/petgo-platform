package com.tailtopia.profile.service;

import com.tailtopia.content.service.ContentService;
import com.tailtopia.profile.domain.PetProfile;
import com.tailtopia.profile.dto.PetListItemResponse;
import com.tailtopia.tailsonality.service.TailsonalityBadgeQuery;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 「我的宠物」列表（V1.4.0 multi-pet AD-3 · {@code GET /pet-profiles/me/list}）。
 *
 * <p>单列一个服务是为了不让 {@link ProfileService} 依赖 {@link ContentService}（后者经
 * {@code ProfileService.ownsPet} 依赖前者，合在一起成环）。宠物数上限 5，逐只计数可接受，不做缓存。
 */
@Service
public class PetListService {

    private final PetResolver petResolver;
    private final ContentService contentService;
    private final TailsonalityBadgeQuery tailsonalityBadges;

    public PetListService(PetResolver petResolver, ContentService contentService,
            TailsonalityBadgeQuery tailsonalityBadges) {
        this.petResolver = petResolver;
        this.contentService = contentService;
        this.tailsonalityBadges = tailsonalityBadges;
    }

    /** 本人全部宠物，按创建时间升序；无宠物 → 空列表（不是 404）。 */
    @Transactional(readOnly = true)
    public List<PetListItemResponse> listMine(long ownerId) {
        List<PetProfile> pets = petResolver.listOwned(ownerId);
        Long defaultId = DefaultPetPolicy.pick(pets).map(PetProfile::getId).orElse(null);
        List<PetListItemResponse> out = new ArrayList<>(pets.size());
        for (PetProfile p : pets) {
            String badge = p.getId() == null ? null : tailsonalityBadges.badgeOf(p.getId()).orElse(null);
            long diaryCount = contentService.countGrowthMoments(ownerId, p.getId());
            out.add(PetListItemResponse.from(p, Objects.equals(p.getId(), defaultId), badge, diaryCount));
        }
        return out;
    }
}
