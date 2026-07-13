package com.tailtopia.pay.refund.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Base64;
import org.junit.jupiter.api.Test;

/**
 * L0：退款 PII AES-GCM 加解密（Story 4.3）。往返一致 + 密文非明文 + 随机 IV + 缺密钥抛异常（不静默存明文）。
 */
class RefundPiiCryptoTest {

    private static final String KEY = Base64.getEncoder()
            .encodeToString("petgo-refund-pii-test-key-32byte".getBytes()); // 32 字节

    @Test
    void encryptDecrypt_roundTrips() {
        RefundPiiCrypto crypto = RefundPiiCrypto.forTest(KEY);
        String plain = "1234567890 · Budi Santoso";
        String ct = crypto.encrypt(plain);
        assertThat(ct).isNotEqualTo(plain);
        assertThat(crypto.decrypt(ct)).isEqualTo(plain);
    }

    @Test
    void encrypt_randomIv_differentCiphertextSamePlaintext() {
        RefundPiiCrypto crypto = RefundPiiCrypto.forTest(KEY);
        assertThat(crypto.encrypt("acct")).isNotEqualTo(crypto.encrypt("acct")); // 随机 IV
    }

    @Test
    void missingKey_throwsNotSilentPlaintext() {
        RefundPiiCrypto crypto = RefundPiiCrypto.forTest("");
        assertThatThrownBy(() -> crypto.encrypt("acct"))
                .isInstanceOf(IllegalStateException.class);
    }
}
