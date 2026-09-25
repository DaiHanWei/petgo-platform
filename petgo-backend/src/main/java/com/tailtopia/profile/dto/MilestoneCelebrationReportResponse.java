package com.tailtopia.profile.dto;

/**
 * 庆祝回报结果（V1.3.0 Story 1.4）。
 *
 * <p>{@code markedCount} 是**实际由 NULL 变为非空**的行数，不是请求里的 code 数 ——
 * 重复回报、或列表里混进已庆祝过的条目，都只会让这个数变小，不会覆盖既有的庆祝时刻（幂等）。
 *
 * <p>{@code uncelebratedCount} 是置位后该宠物剩余的「已完成且未庆祝」数：客户端拿它直接刷新角标，
 * 不必再发一次请求。它也可能**大于 0** —— 那正是回报窗口期内新解锁的条目，下次进列表页补弹。
 *
 * @param markedCount       本次实际置位的条目数
 * @param uncelebratedCount 置位后剩余的未庆祝条目数
 */
public record MilestoneCelebrationReportResponse(
        int markedCount,
        long uncelebratedCount) {
}
