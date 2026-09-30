package com.tailtopia.passport.service;

import com.tailtopia.passport.repository.BoardingPassUnlockRepository;
import com.tailtopia.place.service.PlaceIdentityQuery;
import com.tailtopia.purchase.domain.KeepsakeSku;
import com.tailtopia.purchase.domain.KeepsakeTargetResolver;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 订单中心「查看」目标（V1.3.2 Story 3.6）：解锁行所在场所沿合并链到<b>最终场所</b>的 place token
 * （合并后原卡已改挂 / supersede 到保留方，登机牌详情也按保留方出）；行已删 → empty。
 */
@Component
public class BoardingPassTargetResolver implements KeepsakeTargetResolver {

    private final BoardingPassUnlockRepository unlocks;
    private final PlaceIdentityQuery places;

    public BoardingPassTargetResolver(BoardingPassUnlockRepository unlocks, PlaceIdentityQuery places) {
        this.unlocks = unlocks;
        this.places = places;
    }

    @Override
    public KeepsakeSku sku() {
        return KeepsakeSku.BOARDING_PASS;
    }

    @Override
    public String targetKind() {
        return "BOARDING_PASS";
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<String> targetToken(long refId) {
        return unlocks.findById(refId).flatMap(row -> {
            long placeId = row.getPlaceId();
            long finalId = places.resolveFinal(List.of(placeId)).getOrDefault(placeId, placeId);
            return places.tokenOf(finalId);
        });
    }
}
