package com.tailtopia.profile.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.tailtopia.profile.domain.MilestoneCompletionSource;
import com.tailtopia.profile.domain.MilestoneLevel;
import com.tailtopia.profile.event.MilestoneCompletedEvent;
import com.tailtopia.shared.analytics.AnalyticsClient;
import com.tailtopia.shared.analytics.AnalyticsDistinctId;
import com.tailtopia.shared.analytics.PostHogAnalyticsClient;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * V1.1.2 Story 6.1 · T-12 里程碑达成埋点（L0）。
 *
 * <p>三件事：达成路径映射正确、事件属性只含受控值、以及**与客户端同一个 distinctId**
 * （否则「点了按钮」与「达成里程碑」拼不到同一个人身上，漏斗白做）。
 */
class MilestoneAnalyticsTest {

    /** 记录上报内容的假客户端（不出网）。 */
    private static final class RecordingClient implements AnalyticsClient {
        record Captured(String distinctId, String event, Map<String, Object> props) {
        }

        final List<Captured> captured = new ArrayList<>();

        @Override
        public void capture(String distinctId, String event, Map<String, Object> properties) {
            captured.add(new Captured(distinctId, event, properties));
        }
    }

    @Nested
    @DisplayName("path 映射（纯函数）")
    class PathMapping {

        @Test
        @DisplayName("健康类：M5 → consult；M3/M4/M9 → health_record")
        void healthMilestonesMapToTheirTrigger() {
            assertThat(MilestoneAnalyticsPath.of("C-M5", MilestoneCompletionSource.SYSTEM_AUTO))
                    .isEqualTo("consult");
            for (String code : List.of("C-M3", "D-M4", "G-M9")) {
                assertThat(MilestoneAnalyticsPath.of(code, MilestoneCompletionSource.SYSTEM_AUTO))
                        .as("%s 由健康记录触发", code)
                        .isEqualTo("health_record");
            }
        }

        @Test
        @DisplayName("非健康类的系统自动（计数/组合/档案创建）→ system_auto")
        void otherAutoMapsToSystemAuto() {
            for (String code : List.of("C-S1", "C-S2", "C-M10", "C-L5", "D-L4")) {
                assertThat(MilestoneAnalyticsPath.of(code, MilestoneCompletionSource.SYSTEM_AUTO))
                        .isEqualTo("system_auto");
            }
        }

        @Test
        @DisplayName("用户打卡 → checkin；发布回填 → publish")
        void userDrivenPaths() {
            assertThat(MilestoneAnalyticsPath.of("C-S3", MilestoneCompletionSource.USER_CHECKIN))
                    .isEqualTo("checkin");
            assertThat(MilestoneAnalyticsPath.of("C-S3", MilestoneCompletionSource.PUBLISH))
                    .isEqualTo("publish");
        }

        @Test
        @DisplayName("AC5 线上校验：健康类 + checkin 这个组合必须**原样保留**，不得被改写")
        void healthPlusCheckinIsNotRewritten() {
            // Story 5.2 起后端在写库前就拒绝健康类打卡，所以线上不该出现这个组合。
            // 但如果那道护栏哪天失效了，埋点必须把现场如实报出来 —— 这里若改写成
            // health_record，看板就永远发现不了护栏失效。
            assertThat(MilestoneAnalyticsPath.of("C-M3", MilestoneCompletionSource.USER_CHECKIN))
                    .as("护栏失效的告警信号，不能被埋点擦掉")
                    .isEqualTo("checkin");
        }
    }

    @Nested
    @DisplayName("事件上报")
    class Capture {

        @Test
        @DisplayName("每次达成上报一条 milestone_achieved，属性为 code/level/path 三个受控值")
        void capturesControlledProperties() {
            RecordingClient client = new RecordingClient();
            new MilestoneAnalyticsListener(client).onMilestoneCompleted(
                    new MilestoneCompletedEvent(42L, "C-M5", MilestoneLevel.M, "第一次看兽医",
                            MilestoneCompletionSource.SYSTEM_AUTO));

            assertThat(client.captured).hasSize(1);
            RecordingClient.Captured c = client.captured.getFirst();
            assertThat(c.event()).isEqualTo("milestone_achieved");
            assertThat(c.props()).containsOnlyKeys("code", "level", "path");
            assertThat(c.props()).containsEntry("code", "C-M5").containsEntry("level", "M")
                    .containsEntry("path", "consult");
        }

        @Test
        @DisplayName("distinctId 是哈希，不是自增 id（护栏：对外标识不可枚举）")
        void distinctIdIsHashed() {
            RecordingClient client = new RecordingClient();
            new MilestoneAnalyticsListener(client).onMilestoneCompleted(
                    new MilestoneCompletedEvent(42L, "C-S1", MilestoneLevel.S, "档案创建",
                            MilestoneCompletionSource.SYSTEM_AUTO));

            String id = client.captured.getFirst().distinctId();
            assertThat(id).isEqualTo(AnalyticsDistinctId.of(42L)).hasSize(64).doesNotContain("42");
        }
    }

    @Nested
    @DisplayName("distinctId 与客户端一致")
    class DistinctId {

        @Test
        @DisplayName("已知向量：sha256(\"tailtopia-user-42\") —— 与 Dart 端逐字一致")
        void matchesClientVector() {
            // 该值由 sha256("tailtopia-user-42") 得出；前端 analytics.dart#distinctIdFor 同算法。
            // 若此断言变红，说明两端算法漂了 —— 同一个人会在看板上被算成两个人。
            assertThat(AnalyticsDistinctId.of(42L)).isEqualTo(
                    "f9514799a33d2a201721f3ffc7fa376a077e517c546e2692b45f9a778e3fb4b2");
        }

        @Test
        @DisplayName("同 id 稳定、不同 id 互异")
        void stableAndDistinct() {
            assertThat(AnalyticsDistinctId.of(1L)).isEqualTo(AnalyticsDistinctId.of(1L));
            assertThat(AnalyticsDistinctId.of(1L)).isNotEqualTo(AnalyticsDistinctId.of(2L));
        }
    }

    @Nested
    @DisplayName("凭证缺省时不出网")
    class Disabled {

        @Test
        @DisplayName("key 留空 → isEnabled=false，capture 直接返回（本地/测试默认状态）")
        void noKeyMeansNoTraffic() {
            PostHogAnalyticsClient client = new PostHogAnalyticsClient("", "https://example.invalid");
            assertThat(client.isEnabled()).isFalse();
            // 若这里真发了请求，host 是 .invalid 会抛异常 —— 不抛即证明短路生效。
            client.capture("hash", "milestone_achieved", Map.of("code", "C-S1"));
        }
    }
}
