package com.tailtopia.support.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.tailtopia.support.web.SupportContactController.SupportContactView;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * L0 契约金标：客服联系方式 wire（Story 3-1 AC4 · 契约 K6）。
 *
 * <p>🔓 这是一个**免鉴权**端点，任何人都能拿到这个响应。所以字段集必须钉死：
 * 将来往里塞「客服在线时段」「工单 SLA」之类会让本测试变红，**那正是我们要的** ——
 * 免鉴权的边界是「已经公开的信息」，不是「support 这个前缀」，加字段要重新评一次鉴权。
 */
class SupportContactViewContractTest {

    private final JsonMapper json = JsonMapper.builder()
            .changeDefaultPropertyInclusion(
                    incl -> incl.withValueInclusion(JsonInclude.Include.NON_NULL))
            .build();

    @SuppressWarnings("unchecked")
    private Map<String, Object> wire(Object dto) {
        return json.convertValue(dto, Map.class);
    }

    @Test
    @DisplayName("字段集恰好三项，且不含任何内部 id")
    void hasExactlyTheContractFields() {
        var view = new SupportContactView("081290906953", "+6281290906953", "cs@tailtopia.id");

        assertThat(wire(view).keySet())
                .isEqualTo(Set.of("whatsappNumber", "whatsappE164", "email"));
        assertThat(json.writeValueAsString(view))
                .as("单行表的 id 恒为 1，泄出去没有价值，但它是「顺手把实体直接序列化了」的信号")
                .doesNotContain("\"id\"")
                .doesNotContain("createdAt")
                .doesNotContain("updatedAt");
    }

    @Test
    @DisplayName("两种号码形态都下发：原样写法给展示/复制，E.164 给深链")
    void bothPhoneFormsAreOnTheWire() {
        Map<String, Object> w =
                wire(new SupportContactView("081290906953", "+6281290906953", "cs@tailtopia.id"));

        assertThat(w).containsEntry("whatsappNumber", "081290906953");
        // 🔴 E.164 带 +。wa.me 的路径段不要 +，剥 + 是 URL 构造方（Story 3-3）的事。
        assertThat(w).containsEntry("whatsappE164", "+6281290906953");
    }
}
