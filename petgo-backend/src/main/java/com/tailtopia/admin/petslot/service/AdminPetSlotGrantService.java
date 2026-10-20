package com.tailtopia.admin.petslot.service;

import com.tailtopia.admin.audit.service.AdminAuditService;
import com.tailtopia.admin.audit.service.AuditActions;
import com.tailtopia.admin.shared.time.AdminTime;
import com.tailtopia.profile.slot.AdminSlotGrantCommand;
import com.tailtopia.profile.slot.AdminSlotGrantResult;
import com.tailtopia.profile.slot.PetSlotPurchaseService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 后台运营补发宠物名额（V1.4.0 multi-pet Story 4.3 · AB-34.2 · AD-15）：写入经 owning service
 * {@link PetSlotPurchaseService#grantByAdmin}（锁 / 封顶 / 发放 / reconcile 全在那里），本类只在<b>同一事务</b>里补写审计
 * {@code PET_SLOT_GRANTED}（照 {@code AdminUserService.grantPawCoin} 的摘要写法）。
 *
 * <p>同一 {@code grantToken} 重复提交（双击 / 回退重提）→ {@code replayed = true}：不重复建名额、<b>不重复写审计</b>。
 * 审计摘要含运营填写的原因（界面已提示勿填用户隐私），日志不记原因。
 */
@Service
public class AdminPetSlotGrantService {

    private final PetSlotPurchaseService purchases;
    private final AdminAuditService audit;
    private final AdminTime time;

    public AdminPetSlotGrantService(PetSlotPurchaseService purchases, AdminAuditService audit, AdminTime time) {
        this.purchases = purchases;
        this.audit = audit;
        this.time = time;
    }

    @Transactional
    public AdminSlotGrantResult grant(long userId, int days, String reason, String grantToken, long operatorAdminId) {
        AdminSlotGrantResult result = purchases.grantByAdmin(
                new AdminSlotGrantCommand(userId, days, reason, grantToken, operatorAdminId));
        if (!result.replayed()) {
            audit.record(operatorAdminId, AuditActions.PET_SLOT_GRANTED, "USER", String.valueOf(userId),
                    summary(result.days(), result, reason, grantToken));
        }
        return result;
    }

    /** {@code 补发宠物名额（天数：7；到期：2026-10-20 15:40 WIB；原因：…；幂等键：ADMIN_GRANT:<token>）}（AC7.2）。 */
    String summary(int days, AdminSlotGrantResult result, String reason, String grantToken) {
        return "补发宠物名额（天数：" + days + "；到期：" + time.fmt(result.rentalExpiresAt()) + " WIB；原因："
                + reason.trim() + "；幂等键：" + PetSlotPurchaseService.ADMIN_GRANT_KEY_PREFIX + grantToken.trim() + "）";
    }
}
