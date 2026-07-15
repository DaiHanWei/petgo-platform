package com.tailtopia.shared.pay;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * GemPay 数据完整性签名（GemPay 收款对接）。GemPay <b>逐接口拼接公式不同</b>，一律各字段原样字符串直接相连
 * （无分隔符）后 md5、取十六进制小写。{@code merchantSecret} <b>只进摘要</b>——绝不作为独立字段上送、
 * 绝不入库、绝不落日志（同 {@code MidtransGateway.sha512Hex} 的护栏）。
 *
 * <p>官方 {@code /direct} 验算向量（L0 锁死）：
 * {@code charge("R19K251220_DE4DA303A8AD",50000,"KMB0000","MBayar_QR","f3c53530fc444b3afa63d2c406dd7438","PROJECT001")}
 * → {@code cd4ce010c1c3f459f116678b90b20b6d}。
 */
final class GemPaySignature {

    private GemPaySignature() {
    }

    /** 收款 {@code /direct}：{@code md5(request_id + amount + merchant_id + channel + merchant_secret + project_no)}。 */
    static String charge(String requestId, long amount, String merchantId, String channel,
            String merchantSecret, String projectNo) {
        return md5Hex(requestId + amount + merchantId + channel + merchantSecret + projectNo);
    }

    /** 交易查询 {@code /history}：{@code md5(merchant_id + merchant_secret + project_no)}。 */
    static String history(String merchantId, String merchantSecret, String projectNo) {
        return md5Hex(merchantId + merchantSecret + projectNo);
    }

    /**
     * 收款回调验签。<b>⚠️ UNCONFIRMED</b>——GemPay 文档<b>未给收款回调签名公式</b>（其余接口/放款回调都给了）。
     * 此处对齐放款回调 {@code md5(partner_ref_id + merchant_id + merchant_secret + 'callback')} 的构造做**合理猜测**：
     * {@code md5(request_id + merchant_id + merchant_secret + 'callback')}。
     * <b>上 live 前必须找 GemPay 确认真实公式</b>（见 {@code GEMPAY-INTEGRATION-DESIGN.md §6.3}）。
     * 猜错只会导致合法回调被拒（fail-closed），不会放行伪造——因公式含 {@code merchantSecret}，攻击者无法伪造。
     */
    static String callback(String requestId, String merchantId, String merchantSecret) {
        return md5Hex(requestId + merchantId + merchantSecret + "callback");
    }

    /** md5 十六进制小写（确定性，L0 可断言）。纯 JDK。 */
    static String md5Hex(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            // MD5 必然可用；异常仅记类名语义，绝不外泄 secret。
            throw new PayException("签名摘要不可用");
        }
    }
}
