package com.tailtopia.tailsonality;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * L0：Tailsonality 远程素材（V1.3.2 · 2026-10-05）。16 张角色卡 + 5 张配型卡不打进 App 包，放后端公开静态目录
 * {@code static/tailsonality/}（{@code role_<四字母>.webp} / {@code match_tier<1..5>.webp}），App 出结果时按需下载
 * （{@code TsRemoteArt}）。
 *
 * <p>目录里恰好是这 21 张、不多不少 —— 少一张该类型用户永远看到占位卡，多一张是拼错的孤儿文件。
 */
class TailsonalityRemoteArtAssetTest {

    private static final Path DIR = Path.of("src", "main", "resources", "static", "tailsonality");

    @Test
    void exactlyRoleAndMatchCards() throws IOException {
        Set<String> expected = new TreeSet<>();
        for (char a : "EI".toCharArray())
            for (char b : "NS".toCharArray())
                for (char c : "TF".toCharArray())
                    for (char d : "JP".toCharArray()) expected.add("role_" + a + b + c + d + ".webp");
        for (int tier = 1; tier <= 5; tier++) expected.add("match_tier" + tier + ".webp");

        Set<String> actual;
        try (Stream<Path> files = Files.list(DIR)) {
            actual = files.map(p -> p.getFileName().toString()).collect(Collectors.toCollection(TreeSet::new));
        }
        assertThat(actual).isEqualTo(expected);
    }
}
