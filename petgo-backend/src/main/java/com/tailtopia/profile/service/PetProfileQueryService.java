package com.tailtopia.profile.service;

import com.tailtopia.profile.dto.PetIdentityView;
import com.tailtopia.profile.dto.PetProfileSnapshot;
import com.tailtopia.profile.repository.PetProfileRepository;
import java.time.LocalDate;
import java.time.Period;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 宠物档案只读查询端口（Story 6.7）。供 notify 定时推送扫描跨模块只读取档案快照，
 * 不暴露写逻辑、不让 notify 直访 {@code PetProfileRepository}（保持模块边界）。
 */
@Service
public class PetProfileQueryService {

    private final PetProfileRepository petProfiles;

    public PetProfileQueryService(PetProfileRepository petProfiles) {
        this.petProfiles = petProfiles;
    }

    /**
     * 全量档案快照（定时推送日扫用）。≤500 DAU 单机日扫足够；建档日期按 UTC 折算。
     */
    @Transactional(readOnly = true)
    public List<PetProfileSnapshot> allSnapshots() {
        return petProfiles.findAll().stream()
                .map(p -> new PetProfileSnapshot(
                        p.getId(),
                        p.getOwnerId(),
                        p.getName(),
                        p.getBirthday(),
                        p.getCreatedAt() == null ? null : p.getCreatedAt().atZone(ZoneOffset.UTC).toLocalDate()))
                .toList();
    }

    /**
     * 按账号取宠物身份摘要（兽医工作台会话/待接单/历史用）。单账号单宠物 → 至多一条。
     * {@code ownerId} 为 null（注销匿名化后会话已剥 user_id）→ 返回 empty，调用方对宠物身份兜底。
     * 月龄按 UTC 今日与生日折算的整月数（生日缺失则 null）。
     */
    @Transactional(readOnly = true)
    public Optional<PetIdentityView> findIdentityByOwner(Long ownerId) {
        if (ownerId == null) {
            return Optional.empty();
        }
        return petProfiles.findByOwnerId(ownerId)
                .map(p -> new PetIdentityView(p.getName(), p.getPetType().name(), ageMonths(p.getBirthday())));
    }

    private static Integer ageMonths(LocalDate birthday) {
        if (birthday == null) {
            return null;
        }
        Period age = Period.between(birthday, LocalDate.now(ZoneOffset.UTC));
        long months = age.getYears() * 12L + age.getMonths();
        return (int) Math.max(0L, months);
    }
}
