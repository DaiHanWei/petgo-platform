package com.tailtopia.consult.dto;

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
                "im-conv-1", "RATED", "VET_BANNED", true,
                java.time.Instant.parse("2026-07-13T10:15:00Z"),
                "drh. Test Satu (vettest1)", "https://cdn/v9.jpg", true);

        assertThat(wire(full).keySet()).isEqualTo(Set.of(
                "id", "status", "source", "vetId", "waitingElapsedSeconds", "timedOut",
                "alreadyActive", "imConversationId", "closedReason", "interruptedReason", "rated",
                "suspendDeadlineAt", "vetDisplayName", "vetAvatarUrl", "vetOnline"));
    }

    @Test
    void waitingSessionOmitsNullables() {
        // 排队中：vetId/imConversationId/closedReason/interruptedReason 均 null → NON_NULL 省略。
        // rated 为原始 boolean（false 不省略），故仍在键集内。
        ConsultSessionResponse waiting = new ConsultSessionResponse(
                7L, "WAITING", "DIRECT", null, 0L, false, false, null, null, null, false, null,
                null, null, null);

        assertThat(wire(waiting).keySet()).isEqualTo(Set.of(
                "id", "status", "source", "waitingElapsedSeconds", "timedOut", "alreadyActive", "rated"));
    }

    /**
     * 兽医身份三字段全部可空且 NON_NULL 省略 —— 富化降级（{@code VetPeer.UNKNOWN}）时的线上形态
     * 必须与改动前逐字节一致，老客户端不受影响。
     *
     * <p>尤其 {@code vetOnline} 是**装箱 Boolean 而非原始 boolean**：若写成原始类型，
     * 「未知」会被序列化成 {@code false}，前端就会把「查不到在线状态」显示成「离线」——
     * 那是编出来的状态，与改前恒亮的假在线点是同一类错误。
     */
    @Test
    void vetPeerFieldsAreOmittedWhenUnknown() {
        ConsultSessionResponse.VetPeer unknown = ConsultSessionResponse.VetPeer.UNKNOWN;
        assertThat(unknown.displayName()).isNull();
        assertThat(unknown.avatarUrl()).isNull();
        assertThat(unknown.online()).isNull();

        ConsultSessionResponse degraded = new ConsultSessionResponse(
                7L, "IN_PROGRESS", "DIRECT", 9L, 12L, false, false, "im-conv-1", null, null,
                false, null, unknown.displayName(), unknown.avatarUrl(), unknown.online());

        assertThat(wire(degraded)).doesNotContainKeys("vetDisplayName", "vetAvatarUrl", "vetOnline");
    }

    @Test
    void allSixStatesSerializeVerbatim() {
        for (String state : SIX_STATES) {
            ConsultSessionResponse r = new ConsultSessionResponse(
                    1L, state, "DIRECT", null, 0L, false, false, null, null, null, false, null,
                    null, null, null);
            assertThat(wire(r).get("status")).isEqualTo(state);
        }
    }
}
