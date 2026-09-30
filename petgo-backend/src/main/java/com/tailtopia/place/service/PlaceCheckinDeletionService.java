package com.tailtopia.place.service;

import com.tailtopia.place.repository.PlaceCheckinPetRepository;
import com.tailtopia.place.repository.PlaceVisitRepository;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 场所打卡的删档 / 注销级联（V1.3.2 batch-a Story 1.1 · AC6.1 · 架构 delta AD-17，安全攸关 D1/D2）。
 *
 * <p>由 {@code ProfileDeletionService.deleteByUserId} 在删宠物行<b>之前</b>、同一事务内调用：
 * 先删该宠物的 {@code place_checkin_pets}，再删该用户名下<b>已无任何关联宠物</b>的 {@code place_checkins}。
 * 这样删档后同一天重建宠物也能在同一场所再次打卡（新宠物是新 id，且旧关联行已清）。
 *
 * <p>独立成类：{@code PlaceService} 被反射测试禁止出现 {@code delete*} 公有方法。
 */
@Service
public class PlaceCheckinDeletionService {

    private final PlaceCheckinPetRepository checkinPets;
    private final PlaceVisitRepository checkins;

    public PlaceCheckinDeletionService(PlaceCheckinPetRepository checkinPets,
            PlaceVisitRepository checkins) {
        this.checkinPets = checkinPets;
        this.checkins = checkins;
    }

    /** 须在调用方事务内执行（MANDATORY）：与删宠物行原子，不能单独提交一半。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void deleteForPet(long petId, long userId) {
        checkinPets.deleteByPetProfileId(petId);
        List<Long> orphans = checkins.findOrphanIdsOfUser(userId);
        if (!orphans.isEmpty()) {
            checkins.deleteByIdIn(orphans);
        }
    }
}
