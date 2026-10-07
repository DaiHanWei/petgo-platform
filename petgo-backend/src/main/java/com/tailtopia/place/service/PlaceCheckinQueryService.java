package com.tailtopia.place.service;

import com.tailtopia.place.domain.Place;
import com.tailtopia.place.domain.PlaceAvailability;
import com.tailtopia.place.dto.CheckinPlaceView;
import com.tailtopia.place.repository.PlaceRepository;
import com.tailtopia.place.repository.PlaceVisitRepository;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 打卡的跨模块只读口（V1.3.2 Story 1.5 · AD-10）：content 发帖校验 / 帖子详情场所条经它读，
 * <b>不直接 join</b> {@code place_checkins} / {@code places}。
 *
 * <p>🔴 <b>只能依赖 place 自己的仓库</b>：place 已依赖 content（场所评论 / 照片的审核服务），
 * content 反向依赖本类 —— 只要本类不注入任何 content bean 就不会成环。
 */
@Service
public class PlaceCheckinQueryService {

    private final PlaceVisitRepository checkins;
    private final PlaceRepository places;

    public PlaceCheckinQueryService(PlaceVisitRepository checkins, PlaceRepository places) {
        this.checkins = checkins;
        this.places = places;
    }

    /** 本人的打卡 → id；不存在 / 非本人 → empty（调用方 422，两者刻意不可区分）。 */
    @Transactional(readOnly = true)
    public Optional<Long> findOwnedCheckinId(long userId, String checkinToken) {
        if (checkinToken == null || checkinToken.isBlank()) {
            return Optional.empty();
        }
        return checkins.findByPublicTokenAndUserId(checkinToken, userId).map(c -> c.getId());
    }

    /** 打卡当前所在场所的展示视图（按当前 place_id，合并后指向保留方）；打卡已删 → empty。 */
    @Transactional(readOnly = true)
    public Optional<CheckinPlaceView> findCheckinPlace(long checkinId) {
        return checkins.findById(checkinId)
                .flatMap(c -> places.findById(c.getPlaceId()))
                .map(PlaceCheckinQueryService::viewOf);
    }

    private static CheckinPlaceView viewOf(Place p) {
        return new CheckinPlaceView(p.getPublicToken(), p.getName(),
                PlaceAvailability.of(p.getStatus().name(), p.getDeletedAt()));
    }
}
