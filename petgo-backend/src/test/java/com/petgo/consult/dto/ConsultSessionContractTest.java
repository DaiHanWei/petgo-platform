package com.petgo.consult.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * L0 契约金标：问诊会话状态机 wire（CROSS-STORY-DECISIONS C5）。
 *
 * <p>钉死 {@link ConsultSessionResponse} 字段集 + 6 态字符串契约。三方同步点：
 * <ul>
 *   <li>App   —— {@code petgo_app/lib/features/consult/domain/consult_session.dart}（{@code ConsultSession.fromJson}）</li>
 *   <li>Mock  —— {@code petgo_app/lib/core/mock/mock_backend.dart}（{@code /consult-sessions} POST + {@code /active}）</li>
 * </ul>
 */
class ConsultSessionContractTest {

    private final JsonMapper json = JsonMapper.builder()
            .changeDefaultPropertyInclusion(
                    incl -> incl.withValueInclusion(JsonInclude.Include.NON_NULL))
            .build();

    @SuppressWarnings("unchecked")
    private Map<String, Object> wire(Object dto) {
        return json.convertValue(dto, Map.class);
    }

    /** 状态机 6 态（架构 §Communication + 决策 E1 含 CANCELLED）。App ConsultSession 注释须与此一致。 */
    private static final Set<String> SIX_STATES = Set.of(
            "WAITING", "IN_PROGRESS", "PENDING_CLOSE", "CLOSED", "INTERRUPTED", "CANCELLED");

    @Test
    void fullSessionHasExactlyTheContractFields() {
        ConsultSessionResponse full = new ConsultSessionResponse(
                7L, "IN_PROGRESS", "DIRECT", 9L, 12L, false, true,
                "im-conv-1", "RATED", "VET_BANNED");

        assertThat(wire(full).keySet()).isEqualTo(Set.of(
                "id", "status", "source", "vetId", "waitingElapsedSeconds", "timedOut",
                "alreadyActive", "imConversationId", "closedReason", "interruptedReason"));
    }

    @Test
    void waitingSessionOmitsNullables() {
        // 排队中：vetId/imConversationId/closedReason/interruptedReason 均 null → NON_NULL 省略。
        ConsultSessionResponse waiting = new ConsultSessionResponse(
                7L, "WAITING", "DIRECT", null, 0L, false, false, null, null, null);

        assertThat(wire(waiting).keySet()).isEqualTo(Set.of(
                "id", "status", "source", "waitingElapsedSeconds", "timedOut", "alreadyActive"));
    }

    @Test
    void allSixStatesSerializeVerbatim() {
        for (String state : SIX_STATES) {
            ConsultSessionResponse r = new ConsultSessionResponse(
                    1L, state, "DIRECT", null, 0L, false, false, null, null, null);
            assertThat(wire(r).get("status")).isEqualTo(state);
        }
    }
}
