package com.tailtopia.passport.service;

import com.tailtopia.passport.domain.PassportSnapshot;
import com.tailtopia.passport.repository.PassportSnapshotRepository;
import com.tailtopia.purchase.domain.KeepsakeSku;
import com.tailtopia.purchase.domain.KeepsakeTargetResolver;
import java.util.Optional;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** 订单中心「查看」目标（V1.3.2 Story 3.6）：已付快照的 token（未付 / 已删 → empty）。 */
@Component
public class PassportSnapshotTargetResolver implements KeepsakeTargetResolver {

    private final PassportSnapshotRepository snapshots;

    public PassportSnapshotTargetResolver(PassportSnapshotRepository snapshots) {
        this.snapshots = snapshots;
    }

    @Override
    public KeepsakeSku sku() {
        return KeepsakeSku.PASSPORT_SNAP;
    }

    @Override
    public String targetKind() {
        return "PASSPORT_SNAPSHOT";
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<String> targetToken(long refId) {
        return snapshots.findById(refId).filter(s -> s.getPaidAt() != null).map(PassportSnapshot::getPublicToken);
    }
}
