package com.tailtopia.pay.refund.crypto;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 退款收款账户（payout_account / account_holder_name）字段级加密（Story 4.3，FR-NFR-4，最高危 A-1）。
 *
 * <p>AES-256-GCM：随机 12B IV 前置密文，整体 base64。密钥 {@code refund.pii-key}（env {@code REFUND_PII_KEY}，
 * base64 的 32 字节）——**凭证 env 注入红线，绝不入库/入日志**。密钥缺失时加/解密**抛异常**（不静默存明文）。
 *
 * <p>{@link EncryptedStringConverter} 由 Hibernate 实例化（非 Spring bean），故本组件在构造时把自身
 * 暴露为静态 {@link #INSTANCE} 供 converter 取用（应用启动即注入密钥）。
 */
@Component
public class RefundPiiCrypto {

    private static final String ALGO = "AES/GCM/NoPadding";
    private static final int IV_LEN = 12;
    private static final int TAG_BITS = 128;

    private static volatile RefundPiiCrypto instance;

    private final SecretKeySpec key; // null = 未配置
    private final SecureRandom random = new SecureRandom();

    @Autowired
    public RefundPiiCrypto(@Value("${refund.pii-key:}") String keyB64) {
        this(keyB64, true);
    }

    private RefundPiiCrypto(String keyB64, boolean register) {
        this.key = (keyB64 == null || keyB64.isBlank())
                ? null
                : new SecretKeySpec(Base64.getDecoder().decode(keyB64.trim()), "AES");
        if (register) {
            instance = this; // 生产：应用上下文构造时注入静态供 Hibernate converter 取用
        }
    }

    /** 供 Hibernate 实例化的 converter 取用（应用上下文启动后非 null）。 */
    static RefundPiiCrypto get() {
        RefundPiiCrypto i = instance;
        if (i == null) {
            throw new IllegalStateException("RefundPiiCrypto 尚未初始化（应用上下文未就绪）");
        }
        return i;
    }

    public String encrypt(String plaintext) {
        requireKey();
        try {
            byte[] iv = new byte[IV_LEN];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance(ALGO);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] ct = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] out = new byte[iv.length + ct.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(ct, 0, out, iv.length, ct.length);
            return Base64.getEncoder().encodeToString(out);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("退款 PII 加密失败", e);
        }
    }

    public String decrypt(String ciphertext) {
        requireKey();
        try {
            byte[] all = Base64.getDecoder().decode(ciphertext);
            byte[] iv = new byte[IV_LEN];
            System.arraycopy(all, 0, iv, 0, IV_LEN);
            Cipher cipher = Cipher.getInstance(ALGO);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] pt = cipher.doFinal(all, IV_LEN, all.length - IV_LEN);
            return new String(pt, StandardCharsets.UTF_8);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("退款 PII 解密失败", e);
        }
    }

    private void requireKey() {
        if (key == null) {
            throw new IllegalStateException("REFUND_PII_KEY 未配置——退款收款账户加密不可用（绝不静默存明文）");
        }
    }

    /** 供单测直接注入测试密钥（不经 Spring 容器，且不污染静态 {@link #instance}）。 */
    static RefundPiiCrypto forTest(String keyB64) {
        return new RefundPiiCrypto(keyB64, false);
    }
}
