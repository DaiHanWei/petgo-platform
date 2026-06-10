package com.petgo.profile.service;

import com.petgo.profile.dto.PetProfileSnapshot;
import com.petgo.profile.repository.PetProfileRepository;
import java.time.ZoneOffset;
import java.util.List;
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
}
