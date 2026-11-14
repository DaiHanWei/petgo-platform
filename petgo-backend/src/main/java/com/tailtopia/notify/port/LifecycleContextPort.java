package com.tailtopia.notify.port;

import java.time.LocalDate;
import java.util.Collection;
import java.util.Map;
import java.util.Set;

/**
 * 生命周期推送分层上下文<b>只读端口</b>（V1.5.0 onboarding-recall Story 6.1 · OR-21）：对一批用户<b>一次批量</b>
 * 给出分层与文案所需的账号状态。实现在 profile 侧（{@code ProfileLifecycleContextAdapter}），查询次数与候选数无关。
 * notify 只依赖本接口，不直访宠物 / 新手标记 / 活跃日 / 里程碑表。
 */
public interface LifecycleContextPort {

    /**
     * @param userIds  候选用户
     * @param wibToday 今天（WIB），判 {@code activeToday}
     */
    LifecycleContext contextOf(Collection<Long> userIds, LocalDate wibToday);

    /** 默认宠物（{@code DefaultPetPolicy}，含只读）：推送只用名字 / 品种 / 物种。 */
    record DefaultPet(String name, String breed, String petType) {
    }

    /**
     * @param defaultPets owner → 默认宠物；<b>键存在即「有宠物」</b>
     * @param newbieMarks user → 新手任务标记（{@code user_newbie_marks.task_key}）
     * @param activeToday 今天（WIB）来过的用户（{@code user_active_days}）
     * @param graduated   已有 {@code LULUS_PEMULA} 账号 flag 的用户
     */
    record LifecycleContext(Map<Long, DefaultPet> defaultPets, Map<Long, Set<String>> newbieMarks,
            Set<Long> activeToday, Set<Long> graduated) {

        public static final LifecycleContext EMPTY = new LifecycleContext(Map.of(), Map.of(), Set.of(), Set.of());

        public LifecycleContext {
            defaultPets = defaultPets == null ? Map.of() : defaultPets;
            newbieMarks = newbieMarks == null ? Map.of() : newbieMarks;
            activeToday = activeToday == null ? Set.of() : activeToday;
            graduated = graduated == null ? Set.of() : graduated;
        }

        public Set<String> marksOf(long userId) {
            return newbieMarks.getOrDefault(userId, Set.of());
        }
    }
}
