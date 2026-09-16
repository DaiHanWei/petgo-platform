package com.tailtopia.support.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * L0 单元测试：建单工厂——置 OPEN + token + 归属，contacted/预留字段默认。
 */
class FeedbackTicketTest {

    @Test
    void create_setsOpenStatusAndFields() {
        FeedbackTicket t = FeedbackTicket.create(42L, "tok-abc", "主题", "投诉正文",
                ContactType.EMAIL, "a@b.com", true,
                new FeedbackTicket.ResolvedOrder(7L, RelatedOrderType.CONSULT));

        assertThat(t.getStatus()).isEqualTo(TicketStatus.OPEN);
        assertThat(t.getTicketToken()).isEqualTo("tok-abc");
        assertThat(t.getUserId()).isEqualTo(42L);
        assertThat(t.getSubject()).isEqualTo("主题");
        assertThat(t.getBody()).isEqualTo("投诉正文");
        assertThat(t.getContactType()).isEqualTo(ContactType.EMAIL);
        assertThat(t.getContactValue()).isEqualTo("a@b.com");
        assertThat(t.isNeedContactCustomer()).isTrue();
        assertThat(t.getRelatedOrderId()).isEqualTo(7L);
        assertThat(t.getRelatedOrderType()).isEqualTo(RelatedOrderType.CONSULT);
        // 预留/默认字段本 story 不填
        assertThat(t.isContactedCustomer()).isFalse();
        assertThat(t.getHandledBy()).isNull();
        assertThat(t.getCsatScore()).isNull();
        assertThat(t.getResolvedAt()).isNull();
    }

    @Test
    void create_allowsNullSubjectAndRelatedOrder() {
        FeedbackTicket t = FeedbackTicket.create(1L, "tok", null, "正文",
                ContactType.WHATSAPP, "+62123", false, null);

        assertThat(t.getSubject()).isNull();
        assertThat(t.getRelatedOrderId()).isNull();
        // 🔴 id 为 null 时 type 无意义，但列是 NOT NULL —— 取 CONSULT。
        assertThat(t.getRelatedOrderType()).isEqualTo(RelatedOrderType.CONSULT);
        assertThat(t.isNeedContactCustomer()).isFalse();
        assertThat(t.getStatus()).isEqualTo(TicketStatus.OPEN);
    }
}
