package com.tailtopia.healthreminder.domain;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/** {@link CancelReason} ↔ 小写原文（CHECK 约束值是小写）。 */
@Converter
public class CancelReasonConverter implements AttributeConverter<CancelReason, String> {

    @Override
    public String convertToDatabaseColumn(CancelReason attribute) {
        return attribute == null ? null : attribute.wire();
    }

    @Override
    public CancelReason convertToEntityAttribute(String dbData) {
        return CancelReason.fromWire(dbData);
    }
}
