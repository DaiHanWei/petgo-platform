package com.tailtopia.consult.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.tailtopia.consult.domain.ConsultRequest;
import com.tailtopia.profile.dto.PetIdentityView;
import java.time.Instant;
import java.util.List;

/**
 * 兽医计费队列视图（Story 3.6，{@code GET /api/v1/vet/consultations/queue}）。计费流 {@code consult_requests}
 * 兽医侧读投影，与 V1.0 免费直连流 {@link VetInboxItem}（{@code consult_sessions}）并存不混用。
 *
 * <ul>
 *   <li>{@code awaitingPay}：本兽医当前 {@code ACCEPTED_AWAIT_PAY} 请求（接单后「等待用户支付」中间态，FR-53A），
 *       无则 {@code null}（NON_NULL 省略）。前端据 {@code payDeadlineAt}（服务端权威 timestamptz）渲染倒计时。</li>
 *   <li>{@code available}：可接单的 {@code QUEUEING} 池（FIFO）。<b>兽医忙时（接单中/会话中）为空</b>（不能再接）。</li>
 * </ul>
 *
 * <p><b>V84 起含病例摘要</b>（Story 3.2 [OPEN] 收口 + D1）：兽医接单前据病例判断是否接单，故队列卡为富卡
 * （等级 + 症状摘要 + 图数量）。摘要口径照 {@link VetInboxItem}——<b>列表端点绝不下发签名 URL</b>，
 * 完整病例（含现签图）走 {@code GET /api/v1/vet/consultations/{requestToken}/case}。
 * 身份经 {@code PetProfileQueryService} 批量富化，注销/缺失兜底 null（Jackson NON_NULL 省略）。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record VetQueueResponse(VetAwaitingPayItem awaitingPay, List<VetQueueItem> available) {

    /** 待支付中间态项（FR-53A）：本兽医接单后等待用户支付，含服务端权威支付截止 + 暂停锚（A-4 跳充值）。 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record VetAwaitingPayItem(String requestToken, String petName, Instant payDeadlineAt,
            Instant pausedAt) {

        public static VetAwaitingPayItem of(ConsultRequest r, PetIdentityView pet) {
            return new VetAwaitingPayItem(r.getRequestToken(), pet == null ? null : pet.name(),
                    r.getPayDeadlineAt(), r.getPausedAt());
        }
    }

    /**
     * 队列池项：可接单请求（宠物身份 + 等待时长 + 入队截止 + <b>病例摘要</b>，D1）。
     *
     * <p>{@code source}=DIRECT 时 {@code aiDangerLevel} 为 null（自填病例无 AI 评级），前端据此显「病例」
     * 而非「AI 上下文」标题（照 {@code ConsultAiContextService} 同款语义）。
     * {@code symptomPreview} 截断 40 字、{@code imageCount} 只给数量——<b>列表不下发签名 URL</b>。
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record VetQueueItem(String requestToken, String petName, String petSpecies,
            Integer petAgeMonths, String ownerHandle, long waitingSeconds, Instant queueDeadlineAt,
            String source, String aiDangerLevel, String symptomPreview, int imageCount) {

        /** 症状摘要截断长度（照 {@link VetInboxItem} 同口径）。 */
        private static final int PREVIEW_MAX = 40;

        public static VetQueueItem of(ConsultRequest r, PetIdentityView pet, String ownerHandle) {
            long waiting = r.getCreatedAt() == null ? 0L
                    : Math.max(0L, (System.currentTimeMillis() - r.getCreatedAt().toEpochMilli()) / 1000L);
            String preview = null;
            if (r.getSymptomText() != null) {
                String t = r.getSymptomText();
                preview = t.length() > PREVIEW_MAX ? t.substring(0, PREVIEW_MAX) + "…" : t;
            }
            return new VetQueueItem(r.getRequestToken(),
                    pet == null ? null : pet.name(),
                    pet == null ? null : pet.species(),
                    pet == null ? null : pet.ageMonths(),
                    ownerHandle, waiting, r.getQueueDeadlineAt(),
                    r.getSource() == null ? null : r.getSource().name(),
                    r.getAiDangerLevel(), preview,
                    r.getImageObjectKeys() == null ? 0 : r.getImageObjectKeys().size());
        }
    }
}
