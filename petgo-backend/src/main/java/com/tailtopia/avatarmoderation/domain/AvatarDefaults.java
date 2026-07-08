package com.tailtopia.avatarmoderation.domain;

import java.util.Objects;

/**
 * 平台默认头像约定常量（内容审核 story 5，§4.2）。<b>不新增 {@code is_system_default_avatar} 布尔列</b>——
 * 「当前为默认头像」以 {@code avatar_url == 默认常量} 表达：违规重置即把 {@code avatar_url} 写成对应默认常量，
 * 与「用户重传新值」天然区分，无需额外布尔位。
 *
 * <p>⚠️ 占位常量（dev 落地时确认指向的真实平台默认头像资源，且与前端 {@code avatarUrl==null} 兜底占位视觉一致，
 * R4/§6.2）。这里的 host/path 为占位，实际资源上传后回填同一常量即可（值一处改动全链生效）。
 * L0/L1 下本常量仅作字符串比较用（默认头像本身不再送审，B12），不依赖资源真实存在。
 */
public final class AvatarDefaults {

    /** 用户默认头像（违规重置 USER_AVATAR 写入此值）。 */
    public static final String DEFAULT_USER_AVATAR_URL = "https://static.tailtopia.id/defaults/user-avatar.png";

    /** 宠物默认头像（违规重置 PET_AVATAR 写入此值）。 */
    public static final String DEFAULT_PET_AVATAR_URL = "https://static.tailtopia.id/defaults/pet-avatar.png";

    private AvatarDefaults() {
    }

    /** 该对象类型对应的默认头像常量。 */
    public static String forSubject(AvatarSubjectType type) {
        return type == AvatarSubjectType.USER_AVATAR ? DEFAULT_USER_AVATAR_URL : DEFAULT_PET_AVATAR_URL;
    }

    /**
     * 是否为平台默认头像（任一类型）。送审钩子据此跳过默认头像的送审（防自审循环 B12）：
     * 重置为默认本身不再触发审核。
     */
    public static boolean isDefault(String url) {
        return Objects.equals(url, DEFAULT_USER_AVATAR_URL) || Objects.equals(url, DEFAULT_PET_AVATAR_URL);
    }
}
