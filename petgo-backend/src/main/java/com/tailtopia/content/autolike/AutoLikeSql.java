package com.tailtopia.content.autolike;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;

/** 自动点赞原生查询结果的类型转换（同 {@code ContentService#toInstant} 的写法）。 */
final class AutoLikeSql {

    private AutoLikeSql() {
    }

    static Instant toInstant(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Instant i) {
            return i;
        }
        if (value instanceof OffsetDateTime odt) {
            return odt.toInstant();
        }
        if (value instanceof Timestamp ts) {
            return ts.toInstant();
        }
        throw new IllegalArgumentException("unexpected timestamp type: " + value.getClass().getName());
    }
}
