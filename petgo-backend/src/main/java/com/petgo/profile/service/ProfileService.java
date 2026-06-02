package com.petgo.profile.service;

import com.petgo.profile.domain.PetProfile;
import com.petgo.profile.dto.PetProfileCreateRequest;
import com.petgo.profile.dto.PetProfileResponse;
import com.petgo.profile.repository.PetProfileRepository;
import com.petgo.shared.error.AppException;
import java.util.Optional;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 宠物档案服务（Story 2.2）：创建（单账号单宠物）+ 当前用户档案查询 + 名片 token 生成。
 *
 * <p>{@code ownerId} 一律由调用方从 JWT 取，绝不信任客户端传入（防越权）。
 */
@Service
public class ProfileService {

    private final PetProfileRepository profiles;
    private final CardTokenGenerator tokenGenerator;

    public ProfileService(PetProfileRepository profiles, CardTokenGenerator tokenGenerator) {
        this.profiles = profiles;
        this.tokenGenerator = tokenGenerator;
    }

    /**
     * 创建当前用户的宠物档案。已存在 → 409（单账号单宠物，FR-11）。
     */
    @Transactional
    public PetProfileResponse create(long ownerId, PetProfileCreateRequest req) {
        if (profiles.existsByOwnerId(ownerId)) {
            throw AppException.profileExists("已有宠物档案，V1 暂仅支持单只宠物");
        }
        String name = req.name() == null ? null : req.name().trim();
        if (name == null || name.isEmpty()) {
            throw AppException.validation("宠物名不能为空");
        }
        PetProfile profile = PetProfile.create(
                ownerId,
                name,
                blankToNull(req.avatarUrl()),
                blankToNull(req.breed()),
                req.birthday(),
                blankToNull(req.intro()),
                tokenGenerator.generate());
        try {
            PetProfile saved = profiles.save(profile);
            return PetProfileResponse.from(saved);
        } catch (DataIntegrityViolationException e) {
            // 并发双开窗：唯一约束兜底（owner_id / card_token），归一为 409。
            throw AppException.profileExists("已有宠物档案，V1 暂仅支持单只宠物");
        }
    }

    /** 当前用户档案（无则 404）。供「已有档案直达」与后续 Story 复用。 */
    @Transactional(readOnly = true)
    public PetProfileResponse getMyProfile(long ownerId) {
        return profiles.findByOwnerId(ownerId)
                .map(PetProfileResponse::from)
                .orElseThrow(() -> AppException.notFound("尚未创建宠物档案"));
    }

    @Transactional(readOnly = true)
    public Optional<PetProfile> findByOwnerId(long ownerId) {
        return profiles.findByOwnerId(ownerId);
    }

    public boolean hasProfile(long ownerId) {
        return profiles.existsByOwnerId(ownerId);
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }
}
