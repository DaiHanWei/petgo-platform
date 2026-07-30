package com.tailtopia.pay.refund.crypto;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/**
 * payout PII 字段级加密 converter（Story 4.3）。落库前加密、读出后解密（AES-GCM，见 {@link RefundPiiCrypto}）。
 * 由 Hibernate 实例化，密钥经静态 {@link RefundPiiCrypto#get()} 取用。**仅显式 {@code @Convert} 应用**（非 autoApply）。
 */
@Converter(autoApply = false)
public class EncryptedStringConverter implements AttributeConverter<String, String> {

    @Override
    public String convertToDatabaseColumn(String attribute) {
        return attribute == null ? null : RefundPiiCrypto.get().encrypt(attribute);
    }

    @Override
    public String convertToEntityAttribute(String dbData) {
        return dbData == null ? null : RefundPiiCrypto.get().decrypt(dbData);
    }
}
