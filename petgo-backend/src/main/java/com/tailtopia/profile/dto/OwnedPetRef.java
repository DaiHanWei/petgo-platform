package com.tailtopia.profile.dto;

import com.tailtopia.profile.domain.PetType;

/** 账号名下宠物的最小引用（V1.3.2 Story 2.1 跨模块读口）：id + 物种。 */
public record OwnedPetRef(long petId, PetType petType) {
}
