package com.tailtopia.admin.growth.service;

import com.tailtopia.shared.error.AppException;
import com.tailtopia.shared.error.ErrorTypes;
import java.util.List;
import org.springframework.http.HttpStatus;

/**
 * 成长值规则抽屉校验失败：<b>一次收集全部字段错误</b>（V1.4.0 ① Story 4.1 · UI G3；照 {@code OpsPopupValidationException}）。
 * 仍是 422 的 {@link AppException}，码 = 第一条错误的码；抽屉由 Controller 取 {@link #errors()} 逐字段贴错 + 汇总条。
 */
public class GrowthConfigValidationException extends AppException {

    /**
     * 一条字段错误。
     *
     * @param field 表单字段名（{@code points / dailyCap / groupCap / unitAmount}）
     * @param code  文案码 {@code admin.err.growth.*}
     */
    public record FieldError(String field, String code) {
    }

    private final List<FieldError> errors;

    public GrowthConfigValidationException(List<FieldError> errors) {
        super(HttpStatus.UNPROCESSABLE_ENTITY, ErrorTypes.VALIDATION, "成长值规则有 " + errors.size() + " 处填写有误");
        if (errors.isEmpty()) {
            throw new IllegalArgumentException("errors 不能为空");
        }
        this.errors = List.copyOf(errors);
        code(errors.get(0).code());
    }

    public List<FieldError> errors() {
        return errors;
    }
}
