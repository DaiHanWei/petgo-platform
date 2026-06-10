package com.tailtopia.consult.event;

/**
 * 会话已中断事件（Story 5.7）。封禁兽医导致进行中会话强制中断时发布。
 *
 * <p>供 notify 推送（Epic 6）+ 历史更新。过去式命名。
 * <b>INTERRUPTED 不触发存档</b>（不发 {@code ConsultClosedEvent}，中断非正常结束）。
 */
public record ConsultInterruptedEvent(long sessionId, long userId, long vetId, String reason) {
}
