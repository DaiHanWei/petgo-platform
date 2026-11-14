package com.tailtopia.profile.service;

import com.tailtopia.profile.domain.AccountMilestoneFlag;
import com.tailtopia.profile.domain.PetProfile;
import com.tailtopia.profile.domain.PetType;
import com.tailtopia.profile.domain.UserMilestoneFlag;
import com.tailtopia.profile.dto.AccountMilestoneItemResponse;
import com.tailtopia.profile.dto.AccountMilestoneListResponse;
import com.tailtopia.profile.dto.MilestoneListResponse;
import com.tailtopia.profile.repository.PetProfileRepository;
import com.tailtopia.profile.repository.UserMilestoneFlagRepository;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 账号级里程碑只读查询（V1.5.0 onboarding-recall Story 2.3 · FR-178 · OR-10 / OR-11）。
 *
 * <ul>
 *   <li>{@link #list}：{@code GET /api/v1/me/account-milestones}（没宠物也能看，账号级里程碑页 / 「我的」页入口用）；</li>
 *   <li>{@link #withAccountGroup}：≥ 1.5.0 宠物里程碑列表加账号成就组、S 组与计数去掉账号级（经 {@link MilestoneCounting}）。</li>
 * </ul>
 * 完成态一律以 {@code user_milestone_flags} 为准（账号事实）。
 */
@Service
public class AccountMilestoneQueryService {

    private final UserMilestoneFlagRepository flags;
    private final PetProfileRepository profiles;

    public AccountMilestoneQueryService(UserMilestoneFlagRepository flags, PetProfileRepository profiles) {
        this.flags = flags;
        this.profiles = profiles;
    }

    @Transactional(readOnly = true)
    public AccountMilestoneListResponse list(long ownerId) {
        Map<String, UserMilestoneFlag> byKey = new HashMap<>();
        for (UserMilestoneFlag f : flags.findAllByUserId(ownerId)) {
            byKey.put(f.getFlagKey(), f);
        }
        PetType type = codeSpecies(profiles.findAllByOwnerIdOrderByCreatedAtAscIdAsc(ownerId));
        List<AccountMilestoneItemResponse> items = new ArrayList<>(AccountMilestoneFlag.values().length);
        int done = 0;
        for (AccountMilestoneFlag flag : AccountMilestoneFlag.values()) {
            UserMilestoneFlag row = byKey.get(flag.name());
            boolean completed = row != null;
            if (completed) {
                done++;
            }
            boolean pending = completed && flag == AccountMilestoneFlag.LULUS_PEMULA && row.getCelebratedAt() == null;
            items.add(new AccountMilestoneItemResponse(flag.name(), flag.codeOf(type), completed,
                    completed ? row.getOccurredAt() : null, pending));
        }
        return new AccountMilestoneListResponse(items, done, items.size());
    }

    /** ≥ 1.5.0 宠物里程碑列表新口径（{@code petType} = 这只宠物的物种）。 */
    @Transactional(readOnly = true)
    public MilestoneListResponse withAccountGroup(long ownerId, PetType petType, MilestoneListResponse r) {
        return MilestoneCounting.withAccountGroup(r, petType, flags.findAllByUserId(ownerId));
    }

    /** 账号默认宠物（{@link DefaultPetPolicy}）的物种；无宠物 → CAT（App 三物种同名同图，按 code 查即可）。 */
    static PetType codeSpecies(List<PetProfile> pets) {
        return DefaultPetPolicy.pick(pets).map(PetProfile::getPetType).orElse(PetType.CAT);
    }
}
