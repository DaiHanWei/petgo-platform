package com.tailtopia.support;

import com.tailtopia.shared.i18n.AdminLocaleConfig;
import com.tailtopia.shared.i18n.Messages;

/**
 * standalone MockMvc 测试构造后台控制器时用的 {@link Messages}。
 *
 * <p>刻意用<b>真实</b> MessageSource（同一份三语 bundle）而不是 mock：这样测试里走到的每条
 * 操作提示都会真的去查键，漏键当场以裸键名暴露，而 mock 会把任何 code 都变成一个假字符串、
 * 把漏键藏起来。
 */
public final class TestMessages {

    private static final Messages INSTANCE = new Messages(new AdminLocaleConfig().messageSource());

    public static Messages real() {
        return INSTANCE;
    }

    private TestMessages() {
    }
}
