package com.tailtopia.triage.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.tailtopia.shared.ai.TriageObservation;
import com.tailtopia.triage.domain.DangerLevel;
import com.tailtopia.triage.domain.TriageStatus;
import com.tailtopia.triage.domain.UnlockSource;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * L0 契约金标 —— **安全攸关**：分诊结果三态 wire（CROSS-STORY-DECISIONS C5）。
 *
 * <p>钉死 {@link TriageResultResponse} 的字段集与<b>枚举线格式</b>。红色态判定（App 据 {@code dangerLevel=='RED'}
 * 弹半屏强提醒、零变现）完全依赖此 wire —— 枚举名一旦漂移（如序列化成序数 / 小写 / 改名），红色态会静默漏判，
 * <b>出人命</b>。Story 2.2 加解锁分字段 {@code unlockSource}/{@code locked}（前端 2-4 据 {@code locked} 渲染 paywall）。
 * 三方同步点：
 * <ul>
 *   <li>App   —— {@code petgo_app/lib/features/triage/data/triage_repository.dart}（{@code TriageResult.fromJson}）</li>
 *   <li>Mock  —— {@code petgo_app/lib/core/mock/mock_backend.dart}（{@code /triage/{id}} 分支，轮流 GREEN/YELLOW/RED）</li>
 * </ul>
 */
class TriageResultContractTest {

    private final JsonMapper json = JsonMapper.builder()
            .changeDefaultPropertyInclusion(
                    incl -> incl.withValueInclusion(JsonInclude.Include.NON_NULL))
            .build();

    @SuppressWarnings("unchecked")
    private Map<String, Object> wire(Object dto) {
        return json.convertValue(dto, Map.class);
    }

    @Test
    @SuppressWarnings("unchecked")
    void doneResultHasExactlyTheContractFields() {
        // 已解锁的 DONE（红色态：advice 在场、locked=false）。Story 2.2 后 wire 加 unlockSource/locked。
        TriageResultResponse done = new TriageResultResponse(
                TriageStatus.DONE, DangerLevel.RED, "请立即就医", "ref-x", "免责声明",
                new TriageObservation(List.of("精神状态", "食欲"), "24 小时", List.of("持续呕吐")),
                List.of("将宠物移到阴凉通风处", "用常温水浸湿身体辅助降温"),
                List.of("切勿强行喂食或灌水"),
                UnlockSource.LOCKED, false);

        Map<String, Object> m = wire(done);
        assertThat(m.keySet()).isEqualTo(Set.of(
                "status", "dangerLevel", "advice", "medicationRef", "disclaimer", "observation",
                "emergencySteps", "emergencyAvoid", "unlockSource", "locked"));
        // 红色态对症应急透出契约（App RedAlertOverlay 据此渲染「现在该做 / 切勿」）。
        assertThat((List<String>) m.get("emergencySteps")).containsExactly(
                "将宠物移到阴凉通风处", "用常温水浸湿身体辅助降温");
        assertThat((List<String>) m.get("emergencyAvoid")).containsExactly("切勿强行喂食或灌水");
        assertThat(m.get("locked")).isEqualTo(false);
        // observation 嵌套契约。
        @SuppressWarnings("unchecked")
        Map<String, Object> obs = (Map<String, Object>) m.get("observation");
        assertThat(obs.keySet()).isEqualTo(Set.of("indicators", "timeWindow", "escalationTriggers"));
    }

    @Test
    void dangerLevelSerializesAsUpperSnakeName() {
        // 安全攸关：三态必须落 GREEN/YELLOW/RED 字面名，App _danger 据此映射；绝不可变序数/小写。
        for (DangerLevel lvl : DangerLevel.values()) {
            TriageResultResponse r = new TriageResultResponse(
                    TriageStatus.DONE, lvl, "a", null, "d", null, null, null, UnlockSource.PAID, false);
            assertThat(wire(r).get("dangerLevel")).isEqualTo(lvl.name());
        }
        assertThat(DangerLevel.RED.name()).isEqualTo("RED"); // 红字面锚点
    }

    @Test
    void unlockSourceSerializesAsUpperSnakeName() {
        // Story 2.2：解锁来源落 LOCKED/FREE_QUOTA/PAID 字面名（前端可据来源区分展示）。
        for (UnlockSource src : UnlockSource.values()) {
            TriageResultResponse r = new TriageResultResponse(
                    TriageStatus.DONE, DangerLevel.YELLOW, null, null, "d", null, null, null, src, true);
            assertThat(wire(r).get("unlockSource")).isEqualTo(src.name());
        }
    }

    @Test
    void statusSerializesAsEnumName() {
        for (TriageStatus s : TriageStatus.values()) {
            TriageResultResponse r =
                    new TriageResultResponse(s, null, null, null, null, null, null, null, null, null);
            assertThat(wire(r).get("status")).isEqualTo(s.name());
        }
    }

    @Test
    void notDoneOmitsDangerLevelEntirely() {
        // PENDING/PROCESSING/FAILED → dangerLevel/unlockSource/locked 等全 null → NON_NULL 省略。
        // App 必须把「无 dangerLevel」当未就绪/加载态，绝不可残留上一次 RED。
        TriageResultResponse pending = new TriageResultResponse(
                TriageStatus.PENDING, null, null, null, null, null, null, null, null, null);

        Map<String, Object> m = wire(pending);
        assertThat(m.keySet()).isEqualTo(Set.of("status"));
        assertThat(m).doesNotContainKey("dangerLevel");
        assertThat(m).doesNotContainKey("locked");
        assertThat(m).doesNotContainKey("unlockSource");
    }
}
