package com.tailtopia.admin.seed.service;

import com.tailtopia.content.domain.ContentType;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;

/**
 * 内容指纹（V1.1.6 Story 13.4）。
 *
 * <p>🔴 <b>算法必须与既有那条老路径逐字一致</b>（{@code AdminSeedBatchService#contentHash}）：
 * 两条路径写进的是**同一张指纹表**，算法分叉的表现是"老路径发过的文案，新工作台判不出重复"
 * —— 而那种不一致没人会想到去查。
 *
 * <p>形状：{@code sha256(type + "\n" + text + "\n" + sorted(images))}。
 * 图**排序**是为了让顺序无关的图组产生同一指纹（同一批图换个顺序仍算重复）。
 */
public final class SeedContentFingerprint {

    private SeedContentFingerprint() {
    }

    public static String of(ContentType type, String text, List<String> images) {
        StringBuilder sb = new StringBuilder(type == null ? "DAILY" : type.name())
                .append('\n').append(text == null ? "" : text).append('\n');
        if (images != null) {
            images.stream().sorted().forEach(u -> sb.append(u).append(','));
        }
        try {
            byte[] d = MessageDigest.getInstance("SHA-256")
                    .digest(sb.toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(64);
            for (byte b : d) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
