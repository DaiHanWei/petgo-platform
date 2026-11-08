package com.tailtopia.shared.logistics.biteship;

/**
 * Biteship 调用失败（v1.4.2 shop Story 1.1）。「不可配送」<b>不是</b>失败，不走本异常。
 *
 * <p>🔒 message 只含 kind / HTTP 状态 / Biteship 错误码，不含 key、邮编、地址。
 */
public class BiteshipException extends RuntimeException {

    /** 失败分类。 */
    public enum Kind {
        /** 401 / 403，或 key 未配置。 */
        AUTH,
        /** 402 / 422（余额 / 配额）。 */
        QUOTA,
        /** 429。 */
        RATE_LIMITED,
        /** 其它 4xx。 */
        BAD_REQUEST,
        /** 5xx。 */
        SERVER,
        /** 连接 / 读超时、网络错误。 */
        NETWORK,
        /** 响应解析失败。 */
        PARSE
    }

    private final Kind kind;
    private final Integer httpStatus;
    private final Integer biteshipCode;

    public BiteshipException(Kind kind, Integer httpStatus, Integer biteshipCode) {
        super("biteship " + kind + " http=" + httpStatus + " code=" + biteshipCode);
        this.kind = kind;
        this.httpStatus = httpStatus;
        this.biteshipCode = biteshipCode;
    }

    public Kind getKind() {
        return kind;
    }

    public Integer getHttpStatus() {
        return httpStatus;
    }

    public Integer getBiteshipCode() {
        return biteshipCode;
    }
}
