package com.tailtopia.profile.service;

import com.tailtopia.profile.domain.PetProfile;
import com.tailtopia.profile.repository.PetProfileRepository;
import com.tailtopia.shared.error.AppException;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 「我的宠物」统一解析（V1.4.0 multi-pet AD-2）：带 petId → 校验归属；不带 → 默认宠物（旧版 App 兼容，P-4）。
 *
 * <p>{@code /pet-profiles/me/*} 控制器取宠物<b>只经本类</b>（架构 §5）；默认宠物定义见 {@link DefaultPetPolicy}。
 * 不属于本人与不存在返回同一个 404，不泄露存在性。
 */
@Service
public class PetResolver {

    static final String PET_NOT_FOUND = "宠物档案不存在";
    static final String NO_PROFILE = "尚未创建宠物档案";

    private final PetProfileRepository profiles;

    public PetResolver(PetProfileRepository profiles) {
        this.profiles = profiles;
    }

    /** 有 petId 校验归属，无 petId 取默认宠物；查不到一律 404。 */
    @Transactional(readOnly = true)
    public PetProfile resolve(long ownerId, Long petId) {
        if (petId != null) {
            return profiles.findByIdAndOwnerId(petId, ownerId)
                    .orElseThrow(() -> AppException.notFound(PET_NOT_FOUND));
        }
        return profiles.findDefault(ownerId)
                .orElseThrow(() -> AppException.notFound(NO_PROFILE));
    }

    /** 同 {@link #resolve}，但不带 petId 且无宠物时返回 empty（给「无档案返回 0 / 空」的接口用）。 */
    @Transactional(readOnly = true)
    public Optional<PetProfile> resolveOptional(long ownerId, Long petId) {
        if (petId != null) {
            return Optional.of(resolve(ownerId, petId));
        }
        return profiles.findDefault(ownerId);
    }

    @Transactional(readOnly = true)
    public List<PetProfile> listOwned(long ownerId) {
        return profiles.findAllByOwnerIdOrderByCreatedAtAscIdAsc(ownerId);
    }
}
