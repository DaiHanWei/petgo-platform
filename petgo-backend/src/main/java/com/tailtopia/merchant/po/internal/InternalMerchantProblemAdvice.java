package com.tailtopia.merchant.po.internal;

import com.tailtopia.merchant.po.service.MerchantApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 内部接口错误格式（契约 5.7）：RFC 9457 ProblemDetail，{@code type = urn:tailtopia:internal:merchant:<类型>}，
 * 附加字段放顶层，不带堆栈。只作用于 {@link InternalMerchantPurchaseOrderController}，优先于全局处理器。
 *
 * <p>🔒 日志只记接口错误类型与原因异常类名：不记请求体、门店身份、进货单 token、QRIS 内容。
 */
@RestControllerAdvice(assignableTypes = InternalMerchantPurchaseOrderController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
public class InternalMerchantProblemAdvice {

    private static final Logger log = LoggerFactory.getLogger(InternalMerchantProblemAdvice.class);

    @ExceptionHandler(MerchantApiException.class)
    public ResponseEntity<ProblemDetail> handle(MerchantApiException e) {
        return respond(InternalProblems.of(e.getStatus(), e.getType(), e.getMessage(), e.getExtensions()));
    }

    /** 请求体不是合法 JSON / 字段类型不对 / Content-Type 不对：都是请求不合法。 */
    @ExceptionHandler({HttpMessageNotReadableException.class, HttpMediaTypeNotSupportedException.class})
    public ResponseEntity<ProblemDetail> unreadable(Exception e) {
        return respond(InternalProblems.of(400, MerchantApiException.INVALID_REQUEST, "Malformed request", null));
    }

    @ExceptionHandler(org.springframework.dao.OptimisticLockingFailureException.class)
    public ResponseEntity<ProblemDetail> conflict(Exception e) {
        // 支付回调与取消并发撞版本：给 5xx 让商家平台按「接口不可用」稍后重查，比给一个错误的业务 type 安全。
        log.warn("内部接口并发冲突 cause={}", e.getClass().getSimpleName());
        return respond(InternalProblems.of(503, "unavailable", "Concurrent update, retry later", null));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemDetail> unexpected(Exception e) {
        log.error("内部接口异常 cause={}", e.getClass().getName(), e);
        return respond(InternalProblems.of(500, "internal-error", "Internal error", null));
    }

    private static ResponseEntity<ProblemDetail> respond(ProblemDetail pd) {
        return ResponseEntity.status(pd.getStatus()).contentType(MediaType.APPLICATION_PROBLEM_JSON).body(pd);
    }
}
