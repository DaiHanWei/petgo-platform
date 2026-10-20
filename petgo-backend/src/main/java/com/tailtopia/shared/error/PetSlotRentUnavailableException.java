package com.tailtopia.shared.error;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;

/**
 * 月租名额不可租（V1.4.0 multi-pet Story 2.2 · AD-6）：409 {@code pet-slot-rent-unavailable}。扩展成员 {@code rentable}
 * （= 5 − 名额，名额 5 时 0）；续租超上限时另带 {@code renewableMax}。信封仍由 {@link GlobalExceptionHandler} 统一产出
 * （照 {@link PetReadOnlyException}）。经 {@link AppException#petSlotRentUnavailable} / {@link AppException#petSlotRenewOverLimit} 构造。
 */
public class PetSlotRentUnavailableException extends AppException implements ProblemExtensions {

    private final int rentable;
    private final Integer renewableMax;

    PetSlotRentUnavailableException(int rentable, Integer renewableMax) {
        super(HttpStatus.CONFLICT, ErrorTypes.PET_SLOT_RENT_UNAVAILABLE, "当前不能租用这么多名额");
        this.rentable = rentable;
        this.renewableMax = renewableMax;
    }

    public int getRentable() {
        return rentable;
    }

    public Integer getRenewableMax() {
        return renewableMax;
    }

    @Override
    public Map<String, Object> problemExtensions() {
        Map<String, Object> ext = new LinkedHashMap<>();
        ext.put("rentable", rentable);
        if (renewableMax != null) {
            ext.put("renewableMax", renewableMax);
        }
        return ext;
    }
}
