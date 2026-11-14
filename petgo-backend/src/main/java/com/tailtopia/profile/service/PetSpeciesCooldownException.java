package com.tailtopia.profile.service;

import com.tailtopia.shared.error.AppException;
import com.tailtopia.shared.error.ErrorTypes;
import com.tailtopia.shared.error.ProblemExtensions;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;

/**
 * 宠物类型冷却中（V1.5.0 pet-species Story 1.2 · D-9）：409 {@code pet-species-cooldown}。
 *
 * <p>扩展成员 {@code changeableFrom}（{@code yyyy-MM-dd}，WIB 日期）给 App 冷却提示直接用。整个编辑请求回滚
 * （其他字段也不存，待拍板 Q-2 默认）。信封由 {@code GlobalExceptionHandler} 统一产出（照 {@code PetSlotFullException}）。
 */
public class PetSpeciesCooldownException extends AppException implements ProblemExtensions {

    private final LocalDate changeableFrom;

    public PetSpeciesCooldownException(LocalDate changeableFrom) {
        super(HttpStatus.CONFLICT, ErrorTypes.PET_SPECIES_COOLDOWN, "宠物类型暂时不能修改");
        this.changeableFrom = changeableFrom;
    }

    public LocalDate getChangeableFrom() {
        return changeableFrom;
    }

    @Override
    public Map<String, Object> problemExtensions() {
        Map<String, Object> ext = new LinkedHashMap<>();
        ext.put("changeableFrom", changeableFrom.toString());
        return ext;
    }
}
