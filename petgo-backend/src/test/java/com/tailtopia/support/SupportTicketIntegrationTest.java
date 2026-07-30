package com.tailtopia.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tailtopia.consult.repository.ConsultOrderRepository;
import com.tailtopia.support.domain.FeedbackTicket;
import com.tailtopia.support.dto.CreateTicketRequest;
import com.tailtopia.support.repository.FeedbackTicketRepository;
import com.tailtopia.support.repository.TicketAttachmentRepository;
import com.tailtopia.support.repository.TicketLabelRepository;
import com.tailtopia.support.service.SupportTicketService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

/**
 * L1（需 Docker postgres+redis）。Story 4.1 客服工单模型升级。启动即验 V70 契约（validate）。
 *
 * <p>核心：建单落主表 + 附件 + 标签；≤5 附件超限 422；contact_type 非法 422；内部备注绝不入用户视图（D-3 红线）；
 * 非本人查 404（防枚举）；consult_orders 零污染（关联订单只读）。
 */
class SupportTicketIntegrationTest extends ApiIntegrationTest {

    @Autowired
    private FeedbackTicketRepository tickets;
    @Autowired
    private TicketAttachmentRepository attachments;
    @Autowired
    private TicketLabelRepository labels;
    @Autowired
    private ConsultOrderRepository orders;
    @Autowired
    private SupportTicketService service;

    private String createBody(String contactType, List<String> labelList, List<String> attachmentKeys) throws Exception {
        return json.writeValueAsString(new CreateTicketRequest(
                "订单有问题", "兽医没回复我", contactType, "user@petgo.test", true, null, labelList, attachmentKeys));
    }

    // ---- AC3：建单落主表 + 附件 + 标签 ----

    @Test
    void createTicket_persistsMainAttachmentsAndLabels() throws Exception {
        long userId = newUser().getId();
        long ordersBefore = orders.count();

        String resp = mvc.perform(post("/api/v1/support-tickets")
                        .header(HttpHeaders.AUTHORIZATION, userBearer(userId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody("EMAIL", List.of("BUG", "REFUND"), List.of("k1", "k2", "k3"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.ticketToken").exists())
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andExpect(jsonPath("$.labels.length()").value(2))
                .andExpect(jsonPath("$.attachmentObjectKeys.length()").value(3))
                .andReturn().getResponse().getContentAsString();

        String token = json.readTree(resp).get("ticketToken").asText();
        FeedbackTicket t = tickets.findByTicketToken(token).orElseThrow();
        assertThat(t.getUserId()).isEqualTo(userId);
        assertThat(attachments.findByTicketIdOrderByIdAsc(t.getId())).hasSize(3);
        assertThat(labels.findByTicketIdOrderByIdAsc(t.getId())).hasSize(2);
        // consult_orders 零污染（关联订单只读）
        assertThat(orders.count()).isEqualTo(ordersBefore);
    }

    @Test
    void createTicket_dedupesLabels() throws Exception {
        long userId = newUser().getId();
        String resp = mvc.perform(post("/api/v1/support-tickets")
                        .header(HttpHeaders.AUTHORIZATION, userBearer(userId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody("EMAIL", List.of("BUG", "BUG", "OTHER"), List.of())))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String token = json.readTree(resp).get("ticketToken").asText();
        FeedbackTicket t = tickets.findByTicketToken(token).orElseThrow();
        assertThat(labels.findByTicketIdOrderByIdAsc(t.getId())).hasSize(2); // BUG 去重
    }

    // ---- AC3：≤5 附件 / contact_type 非法 → 422 ----

    @Test
    void createTicket_rejectsSixAttachments() throws Exception {
        long userId = newUser().getId();
        mvc.perform(post("/api/v1/support-tickets")
                        .header(HttpHeaders.AUTHORIZATION, userBearer(userId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody("EMAIL", List.of(), List.of("k1", "k2", "k3", "k4", "k5", "k6"))))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void createTicket_rejectsInvalidContactType() throws Exception {
        long userId = newUser().getId();
        mvc.perform(post("/api/v1/support-tickets")
                        .header(HttpHeaders.AUTHORIZATION, userBearer(userId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody("SMS", List.of(), List.of())))
                .andExpect(status().isUnprocessableEntity());
    }

    // ---- AC4：内部备注绝不入用户视图（D-3 红线）----

    @Test
    void internalNote_notVisibleInUserView() throws Exception {
        long userId = newUser().getId();
        String resp = mvc.perform(post("/api/v1/support-tickets")
                        .header(HttpHeaders.AUTHORIZATION, userBearer(userId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody("EMAIL", List.of("BUG"), List.of())))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String token = json.readTree(resp).get("ticketToken").asText();
        long ticketId = tickets.findByTicketToken(token).orElseThrow().getId();

        service.addInternalNote(ticketId, 999L, "内部机密备注SECRETXYZ");

        String detail = mvc.perform(get("/api/v1/support-tickets/{t}", token)
                        .header(HttpHeaders.AUTHORIZATION, userBearer(userId)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(detail).doesNotContain("内部机密备注SECRETXYZ");
        assertThat(detail).doesNotContain("handledBy");
    }

    // ---- AC4：非本人查 → 404（防枚举）----

    @Test
    void detail_nonOwner_returns404() throws Exception {
        long owner = newUser().getId();
        long intruder = newUser().getId();
        String resp = mvc.perform(post("/api/v1/support-tickets")
                        .header(HttpHeaders.AUTHORIZATION, userBearer(owner))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody("EMAIL", List.of(), List.of())))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String token = json.readTree(resp).get("ticketToken").asText();

        mvc.perform(get("/api/v1/support-tickets/{t}", token)
                        .header(HttpHeaders.AUTHORIZATION, userBearer(intruder)))
                .andExpect(status().isNotFound());
    }

    // ---- 我的工单列表 ----

    @Test
    void myTickets_listsOwnTicketsDesc() throws Exception {
        long userId = newUser().getId();
        for (int i = 0; i < 2; i++) {
            mvc.perform(post("/api/v1/support-tickets")
                            .header(HttpHeaders.AUTHORIZATION, userBearer(userId))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(createBody("EMAIL", List.of(), List.of())))
                    .andExpect(status().isCreated());
        }
        mvc.perform(get("/api/v1/support-tickets")
                        .header(HttpHeaders.AUTHORIZATION, userBearer(userId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));
    }
}
