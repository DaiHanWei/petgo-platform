package com.tailtopia.profile.slot;

import java.util.List;

/**
 * 宠物名连接（V1.4.0 multi-pet Story 2.3 · AC3.2）：1 只「A」、2 只「A &amp; B」、3 只及以上「A, B &amp; C」
 * （id / en 同一写法，{@code &} 前不加逗号）。结果只作 {@code MessageFormat} 的<b>参数</b>传入，不拼进模板。
 *
 * <p>🔒 结果含宠物名：只给通知文案用，不得写日志。
 */
public final class PetNameJoiner {

    private PetNameJoiner() {
    }

    public static String join(List<String> names) {
        if (names == null || names.isEmpty()) {
            return "";
        }
        int n = names.size();
        if (n == 1) {
            return names.get(0);
        }
        return String.join(", ", names.subList(0, n - 1)) + " & " + names.get(n - 1);
    }
}
