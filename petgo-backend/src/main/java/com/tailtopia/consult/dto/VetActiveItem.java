package com.tailtopia.consult.dto;

/**
 * 兽医工作台「进行中」列表项（Active Tab）。活跃态会话（IN_PROGRESS/PENDING_CLOSE）卡片摘要。
 *
 * <p><b>不含 {@code unread}/{@code lastMessage}</b>——这两项是腾讯 IM 侧状态，后端 V1 不入库；
 * 客户端直接读 IM SDK 取未读数与最近一条消息。后端仅提供会话身份与宠物名供卡片渲染。
 *
 * <p>Story B：补机主身份 {@code ownerName}（昵称）/ {@code ownerAvatarUrl}（头像，可空），
 * 供 Active 卡显示「人」的名字与头像（注销/未设则为 null，前端优雅降级到宠物名/首字母）。
 */
public record VetActiveItem(
        long sessionId,
        String source,
        String petName,
        String ownerName,
        String ownerAvatarUrl) {
}
