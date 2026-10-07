package com.tailtopia.passport.domain;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 快照里冻结的一枚章（V1.3.2 Story 3.4 · AC3.3）。落库为 JSON 对象；{@link #placeId} 只供现算章集合 hash，<b>不对外下发</b>。
 *
 * <p>落库形状用基本类型（数字 / 字符串）的 Map，不依赖 JSON 映射器的日期模块。
 */
public record FrozenStamp(long placeId, String placeToken, String placeName, String placeType,
        LocalDate firstVisitDate, long visitCount) {

    public Map<String, Object> toJson() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("placeId", placeId);
        m.put("placeToken", placeToken);
        m.put("placeName", placeName);
        m.put("placeType", placeType);
        m.put("firstVisitDate", firstVisitDate == null ? null : firstVisitDate.toString());
        m.put("visitCount", visitCount);
        return m;
    }

    public static FrozenStamp fromJson(Map<String, Object> m) {
        Object date = m.get("firstVisitDate");
        return new FrozenStamp(
                ((Number) m.get("placeId")).longValue(),
                (String) m.get("placeToken"),
                (String) m.get("placeName"),
                (String) m.get("placeType"),
                date == null ? null : LocalDate.parse(date.toString()),
                m.get("visitCount") instanceof Number n ? n.longValue() : 0L);
    }
}
