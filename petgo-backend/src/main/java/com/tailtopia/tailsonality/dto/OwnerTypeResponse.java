package com.tailtopia.tailsonality.dto;

/** 主人类型（Story 2.5）。未设置 → {@code typeCode = null}，按全局 {@code non_null} 下发为空对象 {@code {}}。 */
public record OwnerTypeResponse(String typeCode) {
}
