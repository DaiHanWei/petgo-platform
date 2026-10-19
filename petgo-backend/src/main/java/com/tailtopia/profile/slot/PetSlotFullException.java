package com.tailtopia.profile.slot;

import com.tailtopia.shared.error.AppException;
import com.tailtopia.shared.error.ErrorTypes;
import com.tailtopia.shared.error.ProblemExtensions;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;

/**
 * 建档时名额已满（V1.4.0 multi-pet Story 1.4 · AD-3）：409 {@code pet-slot-full}。
 *
 * <p>扩展成员给 App「名额已满」抽屉（UI 稿 A4）直接用，不必再请求别的接口：
 * {@code canRent}、{@code nextLevelSlot}（可 null）、{@code petCount}、{@code slotTotal}、{@code unitPrice}（IDR）。
 * 信封仍由 {@code GlobalExceptionHandler} 统一产出（照 {@code CheckoutUnavailableException}）。
 */
public class PetSlotFullException extends AppException implements ProblemExtensions {

    private final boolean canRent;
    private final Integer nextLevelSlot;
    private final int petCount;
    private final int slotTotal;
    private final long unitPrice;

    public PetSlotFullException(PetSlotQuota quota, long unitPrice) {
        super(HttpStatus.CONFLICT, ErrorTypes.PET_SLOT_FULL, "宠物名额已满");
        this.canRent = quota.canRent();
        this.nextLevelSlot = quota.nextLevelSlot();
        this.petCount = quota.petCount();
        this.slotTotal = quota.total();
        this.unitPrice = unitPrice;
    }

    @Override
    public Map<String, Object> problemExtensions() {
        // LinkedHashMap：nextLevelSlot 可为 null（Map.of 不收 null）。
        Map<String, Object> ext = new LinkedHashMap<>();
        ext.put("canRent", canRent);
        ext.put("nextLevelSlot", nextLevelSlot);
        ext.put("petCount", petCount);
        ext.put("slotTotal", slotTotal);
        ext.put("unitPrice", unitPrice);
        return ext;
    }
}
