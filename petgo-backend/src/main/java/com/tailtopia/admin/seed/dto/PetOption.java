package com.tailtopia.admin.seed.dto;

/**
 * 「绑定宠物」下拉里的一项（V1.1.6 Story 12.2 · AC4）。
 *
 * <p>此前这里是个让运营手填**数字 ID** 的 number 输入框，而服务端校验的是
 * "该宠物是否属于所选作者" —— 填错就报错，且运营<b>无从判断原因</b>。
 */
public record PetOption(long id, String name, String petType) {
}
