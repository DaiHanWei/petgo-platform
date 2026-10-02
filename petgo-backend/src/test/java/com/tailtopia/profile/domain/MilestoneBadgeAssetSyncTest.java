package com.tailtopia.profile.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * L0 跨库：里程碑徽章素材两边同步（V1.3.2 Story 5.2 · AD-15 单一素材源）。
 *
 * <p>App {@code assets/milestone/} 是源，后端 {@code static/milestone/} 同批拷入。两边 {@code *.webp}
 * 文件名集合必须完全一致，且逐个 SHA-256 相同（同名不同图也算不一致）——「App 换了新图、网页还是旧图」直接红。
 * 非 {@code .webp}（README）忽略。App 目录找不到即失败，不跳过。
 *
 * <p>待确认 5.7（2026-10-02）：后端 {@code static/milestone/} 是公开静态目录，**不放占位文件**，素材到货时随首批
 * {@code .webp} 一起建 —— 目录不存在按「零枚素材」比对（App 侧有素材而后端没目录照样红）。
 */
class MilestoneBadgeAssetSyncTest {

    private static final Path APP_DIR = Path.of("..", "petgo_app", "assets", "milestone");
    private static final Path BACKEND_DIR = Path.of("src", "main", "resources", "static", "milestone");

    private static Map<String, String> webpHashes(Path dir) throws IOException {
        Map<String, String> out = new TreeMap<>();
        if (dir.equals(BACKEND_DIR) && !Files.exists(dir)) {
            return out;
        }
        assertThat(Files.isDirectory(dir)).as("找不到素材目录 %s（跨库测试不允许跳过）", dir).isTrue();
        try (Stream<Path> files = Files.list(dir)) {
            for (Path f : files.filter(p -> p.getFileName().toString().endsWith(".webp")).toList()) {
                out.put(f.getFileName().toString(), sha256(Files.readAllBytes(f)));
            }
        }
        return out;
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void sameFileNamesAndSameBytesOnBothSides() throws IOException {
        Map<String, String> app = webpHashes(APP_DIR);
        Map<String, String> backend = webpHashes(BACKEND_DIR);
        assertThat(backend.keySet()).as("两边 .webp 文件名集合必须一致（同名同放）").isEqualTo(app.keySet());
        assertThat(backend).as("同名素材内容必须一致（SHA-256）").isEqualTo(app);
    }

    /** 文件名必须是某个语义键或 locked（防拼错的孤儿文件，与 App 侧同一条规则）。 */
    @Test
    void everyBackendWebpIsAKnownKey() throws IOException {
        var allowed = new java.util.HashSet<>(MilestoneBadgeKeys.KEYS.values());
        allowed.add("locked");
        for (String name : webpHashes(BACKEND_DIR).keySet()) {
            assertThat(allowed).contains(name.substring(0, name.length() - ".webp".length()));
        }
    }
}
