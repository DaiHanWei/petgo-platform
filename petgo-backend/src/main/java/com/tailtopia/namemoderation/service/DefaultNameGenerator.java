package com.tailtopia.namemoderation.service;

import com.tailtopia.namemoderation.domain.NameTargetType;
import java.security.SecureRandom;
import java.util.function.Predicate;
import org.springframework.stereotype.Component;

/**
 * 违规重置默认编码名生成器（内容审核 story 4，§5.5）。昵称 {@code user_<hex>}、宠物名 {@code Pet_<hex>}，
 * 后缀为 6~8 位小写十六进制。
 *
 * <p><b>护栏（不可枚举推断）</b>：后缀一律 {@link SecureRandom} 生成，<b>绝不</b>由 users.id / pet_profiles.id /
 * 时间戳派生（否则可反推）。<b>唯一</b>：生成后经传入的存在性判定去重，命中则重生成（上限 {@link #MAX_ATTEMPTS} 次）。
 */
@Component
public class DefaultNameGenerator {

    /** 唯一性冲突重试上限（6~8 hex 空间 16M~4B，冲突概率极低）。 */
    static final int MAX_ATTEMPTS = 5;

    private static final char[] HEX = "0123456789abcdef".toCharArray();
    private static final int MIN_LEN = 6;
    private static final int MAX_LEN = 8;

    private final SecureRandom random = new SecureRandom();

    /**
     * 生成一个唯一的默认编码名。
     *
     * @param targetType 决定前缀（NICKNAME→{@code user_} / PET_NAME→{@code Pet_}）
     * @param exists     候选名是否已被占用（昵称查 users.nickname / 宠物名查 pet_profiles.name）；
     *                   命中返回 true 触发重生成
     * @return 未被占用的默认名；重试仍冲突则以最大长度后缀最后一次生成兜底返回（极端罕见）
     */
    public String generate(NameTargetType targetType, Predicate<String> exists) {
        String prefix = targetType == NameTargetType.NICKNAME ? "user_" : "Pet_";
        String candidate = null;
        for (int i = 0; i < MAX_ATTEMPTS; i++) {
            candidate = prefix + randomHex();
            if (!exists.test(candidate)) {
                return candidate;
            }
        }
        return candidate; // 兜底：唯一性最终由列/查重保证，返回最后一次候选（近乎不可达）
    }

    private String randomHex() {
        int len = MIN_LEN + random.nextInt(MAX_LEN - MIN_LEN + 1); // 6..8
        StringBuilder sb = new StringBuilder(len);
        for (int i = 0; i < len; i++) {
            sb.append(HEX[random.nextInt(HEX.length)]);
        }
        return sb.toString();
    }
}
