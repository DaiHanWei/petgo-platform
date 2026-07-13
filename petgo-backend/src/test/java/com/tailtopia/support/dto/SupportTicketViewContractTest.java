package com.tailtopia.support.dto;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * L0 契约测试（AB-5 隐私红线 D-3）：用户视图 {@link SupportTicketView} **绝不含**内部字段。
 * 编译期字段层锁定——4-4 admin 视图另建，勿把内部字段渗入用户视图。
 */
class SupportTicketViewContractTest {

    private static Set<String> fields() {
        return Arrays.stream(SupportTicketView.class.getRecordComponents())
                .map(RecordComponent::getName)
                .collect(Collectors.toSet());
    }

    @Test
    void userView_excludesInternalFields() {
        assertThat(fields()).doesNotContain(
                "internalNotes",   // 内部备注（用户绝不可见）
                "handledBy",       // 处理人（内部）
                "csRating",        // 客服评级（内部 AB-5G）
                "csatDeadline",    // CSAT 截止（内部调度）
                "relatedOrderId"); // 内部自增 id 不外露
    }

    @Test
    void userView_includesUserVisibleFields() {
        assertThat(fields()).contains(
                "ticketToken", "subject", "body", "contactType", "contactValue",
                "needContactCustomer", "contactedCustomer", "status",
                "labels", "attachmentObjectKeys", "csatScore", "createdAt");
    }
}
