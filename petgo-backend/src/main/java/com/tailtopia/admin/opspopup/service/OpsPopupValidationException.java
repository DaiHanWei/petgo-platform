package com.tailtopia.admin.opspopup.service;

import com.tailtopia.shared.error.AppException;
import com.tailtopia.shared.error.ErrorTypes;
import java.util.List;
import org.springframework.http.HttpStatus;

/**
 * 运营弹窗表单校验失败：<b>一次收集全部字段错误</b>（V1.3.4 后台返工清单 §3 · UI 稿 H7）。
 *
 * <p>仍是 422 的 {@link AppException}，码 = 第一条错误的码 —— 非 htmx 整页提交（flash 一行）与只认单码的调用方照旧可用；
 * 抽屉（htmx）由 Controller 取 {@link #errors()} 逐字段贴错 + 汇总条「还有 N 项需要修改」。
 */
public class OpsPopupValidationException extends AppException {

    /**
     * 一条字段错误。
     *
     * @param slot   抽屉里贴错的位置（一个 {@code .fld} 一个槽：name / image / title / body / button / target /
     *               window / frequency / audience / priority）
     * @param code   文案码 {@code admin.err.opsPopup.*}
     * @param args   文案参数
     * @param inputs 要标红的输入（表单字段名，或上传位容器的 {@code data-error-name}）
     */
    public record FieldError(String slot, String code, Object[] args, List<String> inputs) {
        public FieldError {
            args = args == null ? new Object[0] : args.clone();
            inputs = inputs == null ? List.of() : List.copyOf(inputs);
        }
    }

    private final List<FieldError> errors;

    public OpsPopupValidationException(List<FieldError> errors) {
        super(HttpStatus.UNPROCESSABLE_ENTITY, ErrorTypes.VALIDATION, "运营弹窗表单有 " + errors.size() + " 处需要修改");
        if (errors.isEmpty()) {
            throw new IllegalArgumentException("errors 不能为空");
        }
        this.errors = List.copyOf(errors);
        FieldError first = errors.get(0);
        code(first.code(), first.args());
    }

    public List<FieldError> errors() {
        return errors;
    }

    /** 有错的槽数（汇总条「还有 N 项」：同一字段两处错只算一项）。 */
    public long slotCount() {
        return errors.stream().map(FieldError::slot).distinct().count();
    }
}
