/**
 * TailTopia content 模块 — 审核判定引擎（内容审核 Story 1）。
 *
 * <p>阿里云内容安全接入（stub/live 双模）+ L1/L2/L3 分层词库（白名单优先）+ fail-closed 降级 + 进程内熔断。
 * 门面 {@code ContentModerationService}（content.service）编排本包协作者，对调用方 {@code ContentService} 稳定。
 */
package com.tailtopia.content.moderation;
