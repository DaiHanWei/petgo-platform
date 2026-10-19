package com.tailtopia.gift.domain;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/** {@link GiftSource} ↔ 小写 varchar（{@code gift_orders.source}，CHECK 三值小写）。 */
@Converter
public class GiftSourceConverter implements AttributeConverter<GiftSource, String> {

    @Override
    public String convertToDatabaseColumn(GiftSource attribute) {
        return attribute == null ? null : attribute.wire();
    }

    @Override
    public GiftSource convertToEntityAttribute(String dbData) {
        return dbData == null ? null : GiftSource.fromWire(dbData)
                .orElseThrow(() -> new IllegalStateException("未知送礼入口 source=" + dbData));
    }
}
