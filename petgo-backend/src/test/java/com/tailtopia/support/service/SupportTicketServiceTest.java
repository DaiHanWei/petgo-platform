package com.tailtopia.support.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;

import com.tailtopia.consult.repository.ConsultOrderRepository;
import com.tailtopia.profile.service.CardTokenGenerator;
import com.tailtopia.shared.error.AppException;
import com.tailtopia.support.repository.FeedbackTicketRepository;
import com.tailtopia.support.repository.TicketAttachmentRepository;
import com.tailtopia.support.repository.TicketInternalNoteRepository;
import com.tailtopia.support.repository.TicketLabelRepository;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

/**
 * L0 单元测试：建工单前置校验（≤5 附件 / contact_type / label 非法 → 422），均在落库前抛，不触任何 repo。
 */
@ExtendWith(MockitoExtension.class)
class SupportTicketServiceTest {

    @Mock private FeedbackTicketRepository tickets;
    @Mock private TicketAttachmentRepository attachments;
    @Mock private TicketLabelRepository labels;
    @Mock private TicketInternalNoteRepository internalNotes;
    @Mock private CardTokenGenerator tokenGenerator;
    @Mock private ConsultOrderRepository orders;

    @InjectMocks private SupportTicketService service;

    @Test
    void createTicket_rejectsMoreThan5Attachments() {
        List<String> sixKeys = List.of("k1", "k2", "k3", "k4", "k5", "k6");
        assertThatThrownBy(() -> service.createTicket(1L, "主题", "正文",
                "EMAIL", "a@b.com", true, null, List.of("BUG"), sixKeys))
                .isInstanceOfSatisfying(AppException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY));
        // 超限即拒，绝不落库
        verifyNoInteractions(tickets, attachments, labels);
    }

    @Test
    void createTicket_rejectsInvalidContactType() {
        assertThatThrownBy(() -> service.createTicket(1L, "主题", "正文",
                "SMS", "a@b.com", true, null, List.of(), List.of()))
                .isInstanceOfSatisfying(AppException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY));
        verifyNoInteractions(tickets);
    }

    @Test
    void createTicket_rejectsInvalidLabel() {
        assertThatThrownBy(() -> service.createTicket(1L, "主题", "正文",
                "EMAIL", "a@b.com", true, null, List.of("BUG", "NOPE"), List.of()))
                .isInstanceOfSatisfying(AppException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY));
        verifyNoInteractions(tickets);
    }
}
