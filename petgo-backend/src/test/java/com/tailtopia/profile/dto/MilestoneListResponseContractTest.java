package com.tailtopia.profile.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * L0 契约金标（CROSS-STORY C4/C5）：钉死里程碑列表对外 JSON 形状（Story 8.1 / FR-42）。
 *
 * <p><b>四处必须同步，任一漂移即契约破坏（本测试会红）：</b>
 * <ul>
 *   <li>后端 —— {@link MilestoneListResponse} / {@link MilestoneGroupResponse} / {@link MilestoneItemResponse}</li>
 *   <li>App  —— {@code petgo_app/lib/features/profile/domain/milestone.dart}（8.2 落地）</li>
 *   <li>Mock —— {@code petgo_app/lib/core/mock/mock_backend.dart}（8.2 落地）</li>
 *   <li>本测试字段集</li>
 * </ul>
 *
 * <p>护栏：对外用里程碑 {@code code}（非顺序 id）——响应**不得含任何自增 DB id 字段**。
 * 纯 Jackson 序列化、无 Spring/DB → 云端 headless 可跑（L0）。镜像生产 NON_NULL 配置。
 */
class MilestoneListResponseContractTest {

    private final JsonMapper json = JsonMapper.builder()
            .changeDefaultPropertyInclusion(
                    incl -> incl.withValueInclusion(JsonInclude.Include.NON_NULL))
            .build();

    private static final Set<String> LIST_FIELDS = Set.of(
            "petName", "petAvatarUrl", "completedCount", "totalCount", "groups");
    private static final Set<String> GROUP_FIELDS = Set.of(
            "level", "completedCount", "totalCount", "items");
    private static final Set<String> ITEM_FIELDS = Set.of(
            "code", "title", "level", "triggerType", "completed", "completedAt");

    @SuppressWarnings("unchecked")
    private Map<String, Object> wire(Object dto) {
        return json.convertValue(dto, Map.class);
    }

    @Test
    void completedItemHasExactlyContractFieldsWithUpperSnakeEnums() {
        MilestoneItemResponse item = new MilestoneItemResponse(
                "C-S1", "宠物档案创建完成", "S", "SYSTEM_AUTO", true,
                Instant.parse("2026-06-05T00:00:00Z"));

        Map<String, Object> m = wire(item);
        assertThat(m.keySet()).isEqualTo(ITEM_FIELDS);
        assertThat(m.get("level")).isEqualTo("S");
        assertThat(m.get("triggerType")).isEqualTo("SYSTEM_AUTO");
        assertThat(m.get("code")).isEqualTo("C-S1");
        // 对外用 code，绝不外露自增 id。
        assertThat(m).doesNotContainKey("id");
    }

    @Test
    void uncompletedItemOmitsCompletedAtButKeepsRequired() {
        MilestoneItemResponse item = new MilestoneItemResponse(
                "C-S6", "第一次洗澡", "S", "USER_CHECKIN", false, null);

        Map<String, Object> m = wire(item);
        assertThat(m).doesNotContainKey("completedAt"); // NON_NULL：未完成省略
        assertThat(m.keySet()).isEqualTo(Set.of("code", "title", "level", "triggerType", "completed"));
        assertThat(m.get("completed")).isEqualTo(false);
    }

    @Test
    void groupShapeMatchesContract() {
        MilestoneGroupResponse group = new MilestoneGroupResponse("L", 1, 5, List.of());
        Map<String, Object> m = wire(group);
        assertThat(m.keySet()).isEqualTo(GROUP_FIELDS);
        assertThat(m.get("level")).isEqualTo("L");
        assertThat(m.get("items")).isInstanceOf(List.class);
    }

    @Test
    void listShapeMatchesContractAndOmitsNullAvatar() {
        MilestoneListResponse list = new MilestoneListResponse(
                "Momo", null, 3, 30, List.of());
        Map<String, Object> m = wire(list);
        assertThat(m).doesNotContainKey("petAvatarUrl"); // NON_NULL
        assertThat(m.keySet()).isEqualTo(Set.of("petName", "completedCount", "totalCount", "groups"));
    }

    @Test
    void listWithAvatarHasAllFields() {
        MilestoneListResponse list = new MilestoneListResponse(
                "Momo", "https://cdn/x.jpg", 3, 30, List.of());
        assertThat(wire(list).keySet()).isEqualTo(LIST_FIELDS);
    }
}
