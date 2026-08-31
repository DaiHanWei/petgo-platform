package com.tailtopia.admin.usertag.dto;

/**
 * 用户标签选择器里的一行候选（bug 20260828）。
 *
 * <p>🔴 已注销账号**不会出现在这里**（查询层 {@code UserRepository#searchTaggableUsers} 就滤掉了）
 * —— 这是运营「把标签分给注销用户」那个 bug 的第一道闸；服务层 {@code UserTagQueryService#assign}
 * 是第二道，堵住手填 ID 那条路。
 *
 * <p>⚠️ {@code deactivated}（已停用/封号）**只标注、不隐藏**：运营有时确实要给封号账号
 * 挂「观察中」这类标签，但不标注就等于让他在不知情的情况下分配。
 *
 * @param id          用户 id（提交时用它）
 * @param name        展示名；昵称为空时回落 displayName，仍为空给「(未设昵称)」
 * @param deactivated 已停用（封号）
 * @param virtualAccount 虚拟/种子账号 —— 给它挂标签会显示在种子内容的作者位上
 */
public record TaggableUserRow(long id, String name, boolean deactivated, boolean virtualAccount) {
}
