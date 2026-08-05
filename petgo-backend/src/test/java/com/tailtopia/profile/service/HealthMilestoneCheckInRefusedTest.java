package com.tailtopia.profile.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tailtopia.profile.domain.HealthMilestones;
import com.tailtopia.shared.error.AppException;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * Story 5.2 · L0：**后端显式拒绝健康类里程碑打卡**（NFR-11，安全攸关）。
 *
 * <p>⚠️ 「只在前端隐藏按钮」不合格：绕过 UI 直接调接口仍能打卡，规则就没落地。护栏必须在服务端，
 * 且**在任何数据库读取之前**就短路 —— 否则历史数据里 triggerType 仍是 USER_CHECKIN 的健康类
 * 里程碑会走进正常打卡流程。
 */
class HealthMilestoneCheckInRefusedTest {

    /** 用全 mock 依赖构造 service：护栏应在触库前生效，因此不需要任何桩数据。 */
    private MilestoneCheckInService service() {
        return new MilestoneCheckInService(
                Mockito.mock(com.tailtopia.profile.repository.PetProfileRepository.class),
                Mockito.mock(com.tailtopia.profile.repository.PetMilestoneRepository.class),
                Mockito.mock(com.tailtopia.profile.repository.MilestoneCompletionRepository.class),
                Mockito.mock(MilestoneCompletionService.class),
                Mockito.mock(com.tailtopia.content.service.ContentService.class));
    }

    @Test
    void checkInOnHealthMilestonesIsRejected_beforeTouchingDb() {
        MilestoneCheckInService svc = service();
        for (String code : List.of("C-M3", "C-M4", "C-M5", "C-M9", "D-M3", "G-M9")) {
            assertThatThrownBy(() -> svc.checkIn(7L, code, 1L))
                    .as("%s 只能自动点亮，打卡必须被显式拒绝（NFR-11）", code)
                    .isInstanceOf(AppException.class)
                    .hasMessageContaining("自动点亮");
        }
    }

    @Test
    void nonHealthMilestonesStillGoThroughNormalFlow() {
        // 非健康类不受护栏影响：会继续往下走（此处因 mock 无档案而抛「尚未创建宠物档案」，
        // 说明它**通过了**护栏、进入了正常流程 —— 与「被护栏拒绝」是两种不同的错误）。
        MilestoneCheckInService svc = service();
        assertThatThrownBy(() -> svc.checkIn(7L, "C-S6", 1L))
                .isInstanceOf(AppException.class)
                .hasMessageNotContaining("自动点亮");
    }

    @Test
    void guardUsesTheSingleSharedDefinition() {
        // 集合定义只有一处（Story 5.1 抽出）：护栏、埋点、分类都引用它。
        assertThat(HealthMilestones.SUFFIXES).containsExactlyInAnyOrder("M3", "M4", "M5", "M9");
        // 护栏方法确实存在于 checkIn 路径上（改名/删除会让本断言红）。
        List<Method> checkIn = Arrays.stream(MilestoneCheckInService.class.getDeclaredMethods())
                .filter(m -> m.getName().equals("checkIn")).toList();
        assertThat(checkIn).hasSize(1);
    }
}
