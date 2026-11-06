package com.tailtopia.shop.logistics;

import com.tailtopia.shared.error.AppException;
import com.tailtopia.shared.error.ErrorTypes;
import org.springframework.http.HttpStatus;

/**
 * 询价失败（v1.4.2 shop Story 1.1）→ 503 {@code quote-failed}。不回退固定运费、不落库。
 */
public class QuoteFailedException extends AppException {

    public QuoteFailedException(String detail) {
        super(HttpStatus.SERVICE_UNAVAILABLE, ErrorTypes.QUOTE_FAILED, detail);
    }
}
