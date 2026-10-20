package com.tailtopia.shared.error;

import java.util.Map;
import org.springframework.http.HttpStatus;

/**
 * 只读宠物不可写（V1.4.0 multi-pet Story 2.1 · AD-8）：409 {@code pet-read-only}，扩展成员 {@code petId}
 * 供 App 弹拦截抽屉时定位是哪只宠物。信封仍由 {@link GlobalExceptionHandler} 统一产出（照
 * {@code CheckoutUnavailableException}）。经 {@link AppException#petReadOnly(long)} 构造。
 *
 * <p>{@code petId} 只在归属校验（404）通过之后才会出现在响应里，不泄露他人宠物存在性。
 */
public class PetReadOnlyException extends AppException implements ProblemExtensions {

    private final long petId;

    PetReadOnlyException(long petId) {
        super(HttpStatus.CONFLICT, ErrorTypes.PET_READ_ONLY, "该宠物当前为只读状态");
        this.petId = petId;
    }

    public long getPetId() {
        return petId;
    }

    @Override
    public Map<String, Object> problemExtensions() {
        return Map.of("petId", petId);
    }
}
