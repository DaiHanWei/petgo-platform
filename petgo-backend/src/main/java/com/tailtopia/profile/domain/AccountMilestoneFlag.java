package com.tailtopia.profile.domain;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * 账号级里程碑 flag（V1.4.2 social-topic Story 2.1 · FR-154 · D-4 / D-12）。
 *
 * <p>与按宠物里程碑的区别：触发源是「账号做了什么」（第一次手动关注一个人 / 第一次收藏帖子），账号内只算一次，
 * 记在 {@code user_milestone_flags}；完成时扇到账号下所有 ACTIVE 宠物，各写一条本物种编码的完成行。
 * 通知 / 成长值 / 埋点按账号只算一次（见 {@code AccountMilestoneService}）。
 *
 * <p>🔴 <b>只按完整 code 精确查</b>（{@link #ofCode} / {@link #isAccountLevel}）：通用清单是 {@code G-S12 / G-S13}
 * （决策日志 B1），与猫狗的 {@code S19 / S20} 不对齐，按后缀切分必错。code 一律经 {@link MilestoneAutoCompleteMap} 取。
 *
 * <p>⚠️ 新增 flag：本枚举 + {@link MilestoneAutoEvent} + 映射表 + 新起时间戳迁移 DROP + ADD 重建
 * {@code ck_user_milestone_flags_key}（重列全集）。{@code name()} 即落库的 {@code flag_key}（≤ 32 字符）。
 */
public enum AccountMilestoneFlag {

    FIRST_FOLLOW_USER(MilestoneAutoEvent.FIRST_FOLLOW_USER),
    FIRST_COLLECT_CONTENT(MilestoneAutoEvent.FIRST_COLLECT_CONTENT);

    private final MilestoneAutoEvent event;

    AccountMilestoneFlag(MilestoneAutoEvent event) {
        this.event = event;
    }

    /** 完整 code → flag（构造期由映射表一次算出，三物种全登记）。 */
    private static final Map<String, AccountMilestoneFlag> BY_CODE = Arrays.stream(values())
            .flatMap(f -> f.codes().stream().map(c -> Map.entry(c, f)))
            .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, Map.Entry::getValue));

    /** 对应的自动事件（映射表键）。 */
    public MilestoneAutoEvent event() {
        return event;
    }

    /** 该 flag 在某物种上的完整 code。 */
    public String codeOf(PetType petType) {
        return MilestoneAutoCompleteMap.codeOf(petType, event);
    }

    /** 三物种完整 code（CAT / DOG / OTHER 顺序）。 */
    public List<String> codes() {
        return Arrays.stream(PetType.values()).map(this::codeOf).toList();
    }

    /** 完整 code 精确反查 flag；非账号级 code / null / 空串 → empty。 */
    public static Optional<AccountMilestoneFlag> ofCode(String code) {
        return code == null ? Optional.empty() : Optional.ofNullable(BY_CODE.get(code));
    }

    /** 该完整 code 是否账号级里程碑编码（6 个之一）。 */
    public static boolean isAccountLevel(String code) {
        return ofCode(code).isPresent();
    }
}
