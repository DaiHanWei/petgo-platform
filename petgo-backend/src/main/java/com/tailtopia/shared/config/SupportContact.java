package com.tailtopia.shared.config;

import com.tailtopia.shop.address.domain.IndonesiaPhone;

/**
 * 客服联系方式的两种形态（Story 3-1 / AD-S8）。
 *
 * @param whatsappNumber 运营输入的原样写法（如 {@code 081290906953}）—— <b>展示与复制</b>用，
 *     印尼人认这个形式
 * @param whatsappE164 标准 E.164（如 {@code +6281290906953}）—— <b>深链</b>用
 * @param email 对外客服邮箱
 */
public record SupportContact(String whatsappNumber, String whatsappE164, String email) {

    /**
     * 由原样号码派生 E.164。
     *
     * <p>🔴 <b>返回的 E.164 带 {@code +}，这是标准形态，不要在这里剥掉</b>。
     * {@code wa.me} 的路径段不要 {@code +}，剥 {@code +} 的动作属于 URL 构造方（Story 3-3），
     * provider 只负责给出标准值 —— 在这里迁就某一个消费方，下一个消费方就得反过来拼回去。
     *
     * @throws com.tailtopia.shared.error.AppException 号码不合法时由
     *     {@link IndonesiaPhone#normalize} 抛出；调用方（{@code DbSupportContactProvider}）
     *     负责接住并回退，**不要**让它冒到用户面前
     */
    public static SupportContact of(String whatsappNumber, String email) {
        return new SupportContact(whatsappNumber, IndonesiaPhone.normalize(whatsappNumber), email);
    }
}
