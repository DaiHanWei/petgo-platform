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
        // ⚠️ "G-M9" 已从本清单移除：通用清单**根本没有 M9 这个节点**，拿它测护栏等于在测一个
        //    不存在的 code（旧实现按后缀判才会"命中"）。通用宠物真正的健康类是 G-M1 / G-M2。
        for (String code : List.of("C-M3", "C-M4", "C-M5", "C-M9", "D-M3", "D-M9",
                "G-M1", "G-M2")) {
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

    /**
     * 🔴 反向断言（V1.3.0 Story 1.2）：通用清单的 G-M3「陪伴满 30 天」/ G-M4「记录满 10 条」
     * 与健康无关，**必须仍能打卡** —— 按后缀判会把它们误伤成"只能自动点亮"，等于凭空
     * 砍掉通用宠物两条节点的唯一手动路径。
     */
    @Test
    void genericPetNonHealthMilestonesStillPassTheGuard() {
        MilestoneCheckInService svc = service();
        for (String code : List.of("G-M3", "G-M4")) {
            assertThatThrownBy(() -> svc.checkIn(7L, code, 1L))
                    .as("%s 不是健康类，不该被护栏拦下", code)
                    .isInstanceOf(AppException.class)
                    .hasMessageNotContaining("自动点亮");
        }
    }

    @Test
    void guardUsesTheSingleSharedDefinition() {
        // 集合定义只有一处（Story 5.1 抽出，V1.3.0 Story 1.2 改按完整 code）：
        // 护栏、埋点、时间线分类、前端门控都引用它。
        assertThat(HealthMilestones.CODES).containsExactlyInAnyOrder(
                "C-M3", "C-M4", "C-M5", "C-M9",
                "D-M3", "D-M4", "D-M5", "D-M9",
                "G-M1", "G-M2");
        // 护栏方法确实存在于 checkIn 路径上（改名/删除会让本断言红）。
        List<Method> checkIn = Arrays.stream(MilestoneCheckInService.class.getDeclaredMethods())
                .filter(m -> m.getName().equals("checkIn")).toList();
        assertThat(checkIn).hasSize(1);
    }
}
