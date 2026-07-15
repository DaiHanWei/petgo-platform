package com.tailtopia.shared.pay;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * L0：GemPay md5 签名确定性 + 官方向量锁死（无凭证、无网络，纯 JDK）。
 */
class GemPaySignatureTest {

    @Test
    void chargeMatchesOfficialVector() {
        // GemPay 文档官方 /direct 验算向量。
        String sig = GemPaySignature.charge(
                "R19K251220_DE4DA303A8AD", 50000L, "KMB0000", "MBayar_QR",
                "f3c53530fc444b3afa63d2c406dd7438", "PROJECT001");
        assertThat(sig).isEqualTo("cd4ce010c1c3f459f116678b90b20b6d");
    }

    @Test
    void md5HexIsDeterministicLowerHex() {
        String a = GemPaySignature.md5Hex("abc123");
        String b = GemPaySignature.md5Hex("abc123");
        assertThat(a).isEqualTo(b).hasSize(32).matches("[0-9a-f]+");
    }

    @Test
    void chargeConcatenatesAmountAsIntegerNoDecimal() {
        // amount 拼接为整数（IDR 无小数）——与 md5Hex 明文拼接一致，防 "50000.0" 之类回归。
        String viaCharge = GemPaySignature.charge("R1", 50000L, "M1", "MBayar_QR", "SEC", "P1");
        String viaPlain = GemPaySignature.md5Hex("R1" + "50000" + "M1" + "MBayar_QR" + "SEC" + "P1");
        assertThat(viaCharge).isEqualTo(viaPlain);
    }

    @Test
    void historyFormula() {
        // 交易查询：md5(merchant_id + merchant_secret + project_no)。
        String a = GemPaySignature.history("KMB0000", "SEC", "PROJECT001");
        String b = GemPaySignature.md5Hex("KMB0000" + "SEC" + "PROJECT001");
        assertThat(a).isEqualTo(b).hasSize(32).matches("[0-9a-f]+");
    }

    @Test
    void callbackMatchesRealSandboxVector() {
        // 2026-07-15 真实 sandbox 回调反推确认：收款回调 = /direct 同式。
        String sig = GemPaySignature.callback(
                "STAGCB1784102287", "10000", "KMB0064", "MBayar_QR",
                "2dbe52f2ae7bf285eff585e9291f60ee", "NO8989");
        assertThat(sig).isEqualTo("808e13c586ef44893c9d86d98a08e00e");
    }

    @Test
    void callbackEqualsChargeFormula() {
        // 回调与 /direct 同式：同参应产出同签名。
        String cb = GemPaySignature.callback("R1", "50000", "M1", "MBayar_QR", "SEC", "P1");
        String ch = GemPaySignature.charge("R1", 50000L, "M1", "MBayar_QR", "SEC", "P1");
        assertThat(cb).isEqualTo(ch);
    }
}
