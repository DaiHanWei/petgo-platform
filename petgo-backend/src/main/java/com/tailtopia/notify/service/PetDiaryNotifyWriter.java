package com.tailtopia.notify.service;

import com.tailtopia.notify.repository.PetDiaryNotifyLogDao;
import java.time.LocalDate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 追更日提醒「登记去重日志 + 写通知」的<b>同一事务</b>（V1.4.2 social-topic · Story 1.2 · AC6.4 / AC6.9 · 复审 1）。
 *
 * <p>两步同成同败：写通知失败 → 日志行随事务回滚，当天这只宠物的名额不被白占，监听器可回退到下一只宠物。
 * 独立 bean（{@link PetDiaryNotifyListener} 经代理调用，不自调用）；{@code REQUIRES_NEW}：监听器在 {@code @Async}
 * 线程里无事务，每位追更者各自提交。日志插入与 {@link NotificationService#sendPetDiaryPosted} 都是 {@code REQUIRED}，
 * 加入本事务；推送登记在本事务提交之后。
 */
@Component
public class PetDiaryNotifyWriter {

    private final PetDiaryNotifyLogDao logDao;
    private final NotificationService notifications;

    public PetDiaryNotifyWriter(PetDiaryNotifyLogDao logDao, NotificationService notifications) {
        this.logDao = logDao;
        this.notifications = notifications;
    }

    /**
     * @return {@code true} = 登记成功并已写通知；{@code false} = 今天（WIB）已提醒过这只宠物，什么都没写
     * @throws RuntimeException 写通知失败（事务回滚，日志不残留）
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean notifyIfFirstToday(long followerId, long postId, long petId, String petName, LocalDate wibDate) {
        if (!logDao.tryInsert(followerId, petId, wibDate)) {
            return false;
        }
        notifications.sendPetDiaryPosted(followerId, postId, petId, petName);
        return true;
    }
}
