package com.tailtopia.collect.service;

import com.tailtopia.collect.repository.PlaceCollectionRepository;
import com.tailtopia.place.domain.Place;
import com.tailtopia.place.domain.PlaceStatus;
import com.tailtopia.place.repository.PlaceRepository;
import com.tailtopia.shared.error.AppException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 场所收藏（V1.4.0 ④ Story 2.1 · AD-9 · P-6）。<b>无任何事件、无成长值、不展示计数</b>（PRD）。
 *
 * <p>合并口径（只跳一层，与 {@code PlaceRepository.resolveForView} 一致）：收藏一律落在解析后的保留场所上；
 * 取消时删掉本人在同一显示条目上的全部行。
 */
@Service
public class PlaceCollectService {

    private final PlaceRepository places;
    private final PlaceCollectionRepository collections;

    public PlaceCollectService(PlaceRepository places, PlaceCollectionRepository collections) {
        this.places = places;
        this.collections = collections;
    }

    /** 收藏：不存在 / 下架 / 软删 → 404（与详情同文案）；MERGED → 落在保留场所上。幂等。 */
    @Transactional
    public boolean collect(String token, long me) {
        Place target = places.resolveForView(token).orElseThrow(() -> AppException.notFound("场所不存在"));
        if (!collections.existsCovering(me, target.getId())) {
            collections.insertIfAbsent(me, target.getId());
        }
        return true;
    }

    /** 取消：按 token 找原场所行（含下架与软删）；找不到 → 照常 {@code false}（幂等、永不 404）。 */
    @Transactional
    public boolean uncollect(String token, long me) {
        places.findAnyByPublicToken(token).ifPresent(raw -> {
            long targetId = raw.getStatus() == PlaceStatus.MERGED && raw.getMergedIntoId() != null
                    ? raw.getMergedIntoId() : raw.getId();
            collections.deleteForDisplayItem(me, raw.getId(), targetId);
        });
        return false;
    }
}
