package com.tailtopia.content.autolike;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import org.springframework.stereotype.Component;

/**
 * 自动点赞对照组（V1.5.0 FR-223，架构 L-11）：按<b>作者</b>分组，不落名单（A-AC48）。
 *
 * <p>{@code SHA-256(salt + ":" + userId)} 取前 8 字节按无符号 long 对 100 取余，{@code < holdoutPercent} 即对照组。
 * 同一 id 恒定；盐各环境相同、不进 env（改盐 = 换一批对照组）。
 */
@Component
public class AutoLikeHoldout {

    private final AutoLikeProperties props;

    public AutoLikeHoldout(AutoLikeProperties props) {
        this.props = props;
    }

    public boolean isHoldout(long userId) {
        return isHoldout(props.getHoldoutSalt(), props.getHoldoutPercent(), userId);
    }

    /** 纯函数版本（测试与离线分析用）。 */
    public static boolean isHoldout(String salt, int holdoutPercent, long userId) {
        if (holdoutPercent <= 0) {
            return false;
        }
        byte[] digest = sha256((salt + ":" + userId).getBytes(StandardCharsets.UTF_8));
        long v = ByteBuffer.wrap(digest, 0, 8).getLong();
        return Long.remainderUnsigned(v, 100) < holdoutPercent;
    }

    private static byte[] sha256(byte[] input) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(input);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
