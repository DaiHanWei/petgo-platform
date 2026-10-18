package com.tailtopia.profile.service;

import com.tailtopia.profile.domain.PetProfile;
import com.tailtopia.profile.dto.PetView;
import com.tailtopia.profile.repository.PetProfileRepository;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 跨模块宠物只读口（V1.4.0 multi-pet Story 3.1；③ 送礼 / content 详情依赖，<b>签名定死</b>）。
 *
 * <p>薄封装：按账号列表复用 {@link PetResolver#listOwned}（创建顺序），按 id 取走仓储主键查询，不另写 SQL。
 * <b>不校验归属、不校验只读</b>（③ 只读宠物可收礼，PRD §6.3 注）——调用方各自判。
 */
@Service
public class PetQueryService {

    private final PetResolver petResolver;
    private final PetProfileRepository profiles;

    public PetQueryService(PetResolver petResolver, PetProfileRepository profiles) {
        this.petResolver = petResolver;
        this.profiles = profiles;
    }

    /** 账号下全部宠物（含只读），按创建顺序（{@code created_at ASC, id ASC}）。 */
    @Transactional(readOnly = true)
    public List<PetView> listByOwner(long userId) {
        return petResolver.listOwned(userId).stream().map(PetQueryService::toView).toList();
    }

    /** 按 id 取；不存在 → empty。不校验归属、不校验只读。 */
    @Transactional(readOnly = true)
    public Optional<PetView> get(long petId) {
        return profiles.findById(petId).map(PetQueryService::toView);
    }

    /** 批量按 id 取（一条查询，禁逐只查）：{@code petId → 视图}；null 忽略，已删 / 不存在的不在 Map 里。 */
    @Transactional(readOnly = true)
    public Map<Long, PetView> getAll(Collection<Long> petIds) {
        if (petIds == null || petIds.isEmpty()) {
            return Map.of();
        }
        Set<Long> ids = petIds.stream().filter(Objects::nonNull).collect(Collectors.toSet());
        if (ids.isEmpty()) {
            return Map.of();
        }
        Map<Long, PetView> out = new HashMap<>();
        for (PetProfile p : profiles.findAllById(ids)) {
            out.put(p.getId(), toView(p));
        }
        return out;
    }

    private static PetView toView(PetProfile p) {
        return new PetView(p.getId(), p.getOwnerId(), p.getName(), p.getAvatarUrl(), p.getPetType(),
                p.getStatus(), p.getCreatedAt());
    }
}
