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
 * <p><b>无病例字段</b>：{@code consult_requests} 不存 symptom/AI 危险等级/照片（3-1 表未含），故队列卡仅宠物身份 +
 * 等待时长（区别于免费流 {@link VetInboxItem} 的富卡）。身份经 {@code PetProfileQueryService} 批量富化，
 * 注销/缺失兜底 null（Jackson NON_NULL 省略）。
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

    /** 队列池项：可接单请求（宠物身份 + 等待时长 + 入队截止），无病例字段。 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record VetQueueItem(String requestToken, String petName, String petSpecies,
            Integer petAgeMonths, String ownerHandle, long waitingSeconds, Instant queueDeadlineAt) {

        public static VetQueueItem of(ConsultRequest r, PetIdentityView pet, String ownerHandle) {
            long waiting = r.getCreatedAt() == null ? 0L
                    : Math.max(0L, (System.currentTimeMillis() - r.getCreatedAt().toEpochMilli()) / 1000L);
            return new VetQueueItem(r.getRequestToken(),
                    pet == null ? null : pet.name(),
                    pet == null ? null : pet.species(),
                    pet == null ? null : pet.ageMonths(),
                    ownerHandle, waiting, r.getQueueDeadlineAt());
        }
    }
}
