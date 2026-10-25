package com.tailtopia.merchant.po.internal;

import java.net.URI;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

/** 契约 5.7 的 ProblemDetail：{@code type = urn:tailtopia:internal:merchant:<类型>}，附加字段放顶层（snake_case）。 */
final class InternalProblems {

    static final String TYPE_PREFIX = "urn:tailtopia:internal:merchant:";

    private InternalProblems() {
    }

    static ProblemDetail of(int status, String type, String detail, Map<String, Object> extensions) {
        ProblemDetail pd = ProblemDetail.forStatus(status);
        pd.setType(URI.create(TYPE_PREFIX + type));
        HttpStatus hs = HttpStatus.resolve(status);
        pd.setTitle(hs == null ? type : hs.getReasonPhrase());
        pd.setDetail(detail);
        if (extensions != null) {
            extensions.forEach(pd::setProperty);
        }
        return pd;
    }

    /** 过滤器层直接写出（还没进 MVC）。 */
    static String json(int status, String type) {
        HttpStatus hs = HttpStatus.resolve(status);
        String title = hs == null ? type : hs.getReasonPhrase();
        return "{\"type\":\"" + TYPE_PREFIX + type + "\",\"title\":\"" + title + "\",\"status\":" + status
                + ",\"detail\":\"" + type + "\"}";
    }
}
