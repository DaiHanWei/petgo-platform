package com.tailtopia.support.web;

import com.tailtopia.shared.config.SupportContact;
import com.tailtopia.shared.config.SupportContactProvider;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 客服联系方式下发（Story 3-1 / AD-S8 / 契约 K6）。
 *
 * <p>{@code GET /api/v1/support/contact} → {@code {whatsappNumber, whatsappE164, email}}。
 *
 * <p>🔓 <b>免鉴权</b>：客服弹窗在**登录前**也会出现（兽医登录页就有一个），登录墙会让
 * 「登不进去的人找客服」这条路直接断掉。吐的内容是公司对外公布的号码与邮箱，本来就印在
 * 法务页和 App 里，非敏感。
 *
 * <p>🔴 <b>放行规则必须精确匹配 {@code /api/v1/support/contact}，不得写成
 * {@code /api/v1/support/**}</b>：隔壁 {@code /api/v1/support-tickets/**} 是 USER 角色专属，
 * 通配会给未来任何 {@code /api/v1/support/*} 子路径开天窗。
 *
 * <p>🔴 <b>响应体永远只有这三个字段</b>。将来想往里塞「客服在线时段」「工单 SLA」之类，
 * 要重新评一次鉴权 —— 免鉴权的边界是「已经公开的信息」，不是「support 这个前缀」。
 */
@RestController
public class SupportContactController {

    private final SupportContactProvider contacts;

    public SupportContactController(SupportContactProvider contacts) {
        this.contacts = contacts;
    }

    /**
     * @param whatsappNumber 原样写法，展示与复制用
     * @param whatsappE164 标准 E.164（带 {@code +}），深链用
     */
    public record SupportContactView(String whatsappNumber, String whatsappE164, String email) {

        static SupportContactView of(SupportContact c) {
            return new SupportContactView(c.whatsappNumber(), c.whatsappE164(), c.email());
        }
    }

    @GetMapping("/api/v1/support/contact")
    public SupportContactView contact() {
        return SupportContactView.of(contacts.contact());
    }
}
