package com.tailtopia.passport.service;

import com.tailtopia.passport.domain.PassportSnapshot;
import com.tailtopia.passport.repository.PassportSnapshotRepository;
import com.tailtopia.place.domain.PlaceStampRef;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 护照版本状态（V1.3.2 Story 3.4 · AC5）：护照页与发起购买<b>同一套判定</b>。
 *
 * <p>当前版本已解锁 = 存在已付快照且其现算章集合 hash == 当前章集合现算 hash。次数变化 / 换章面 / 下架都不改集合，
 * 合并后两边都解析到保留方 → 仍判当前；新盖一枚章 → 集合变了 → false（整本重新带水印）。
 */
@Service
public class PassportVersionQuery {

    private final PassportSnapshotRepository snapshots;
    private final PlaceSetHash hash;

    public PassportVersionQuery(PassportSnapshotRepository snapshots, PlaceSetHash hash) {
        this.snapshots = snapshots;
        this.hash = hash;
    }

    /** @param current 当前章（{@code PlaceStampQueryService.stampRefsOf}）；空 → 未解锁 */
    @Transactional(readOnly = true)
    public VersionState stateOf(long petId, List<PlaceStampRef> current) {
        List<PassportSnapshot> paid = snapshots.findByPetProfileIdAndPaidAtIsNotNullOrderByPaidAtDescIdDesc(petId);
        if (current.isEmpty()) {
            return new VersionState(false, paid.size());
        }
        String now = hash.of(current.stream().map(PlaceStampRef::placeId).toList());
        boolean unlocked = paid.stream().anyMatch(s -> hash.of(s.placeIds()).equals(now));
        return new VersionState(unlocked, paid.size());
    }

    public record VersionState(boolean currentVersionUnlocked, int purchasedVersionCount) {
    }
}
