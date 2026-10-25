package com.tailtopia.merchant.po.service;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 内部接口的契约错误（契约 5.7）。{@code type} 只给末段（如 {@code price-changed}），
 * 由 {@code InternalMerchantProblemAdvice} 拼成 {@code urn:tailtopia:internal:merchant:<type>} 的 ProblemDetail；
 * {@code extensions} 放 ProblemDetail 顶层（snake_case 键）。
 *
 * <p>🔴 只在内部接口这条路径上用，App 侧仍走 {@code AppException}：两边的 type 命名空间与键名风格不同，混用会让
 * 一边的错误以另一边的格式漏出去。
 */
public class MerchantApiException extends RuntimeException {

    public static final String INVALID_REQUEST = "invalid-request";
    public static final String NOT_FOUND = "not-found";
    public static final String STORE_DISABLED = "store-disabled";
    public static final String PRICE_CHANGED = "price-changed";
    public static final String OUT_OF_STOCK = "out-of-stock";
    public static final String IDEMPOTENCY_CONFLICT = "idempotency-conflict";
    public static final String NOT_PAYABLE = "not-payable";
    public static final String UNAUTHORIZED = "unauthorized";
    public static final String FORBIDDEN = "forbidden";

    private final int status;
    private final String type;
    private final transient Map<String, Object> extensions;

    public MerchantApiException(int status, String type, String detail, Map<String, Object> extensions) {
        super(detail);
        this.status = status;
        this.type = type;
        this.extensions = extensions == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(extensions));
    }

    public static MerchantApiException invalid(String detail) {
        return new MerchantApiException(400, INVALID_REQUEST, detail, null);
    }

    public static MerchantApiException notFound() {
        return new MerchantApiException(404, NOT_FOUND, "Purchase order not found", null);
    }

    public static MerchantApiException storeDisabled() {
        return new MerchantApiException(403, STORE_DISABLED, "Store is not active", null);
    }

    public int getStatus() {
        return status;
    }

    public String getType() {
        return type;
    }

    public Map<String, Object> getExtensions() {
        return extensions;
    }
}
