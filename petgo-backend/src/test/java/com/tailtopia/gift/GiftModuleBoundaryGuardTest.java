package com.tailtopia.gift;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * V1.4.0 gift Story 1.1 · AC11.3 · L0 源码守卫：送礼模块的边界（架构 AD-5 / P-3 / §5）。
 *
 * <ol>
 *   <li>不 import 别的模块的仓储（{@code pay} / {@code profile} / {@code content}）—— 钱只经钱包 / 意图服务，宠物只经自有端口；</li>
 *   <li>除单宠过渡实现 {@code SinglePetGiftPetDirectory} 外不出现 {@code pet_profiles} / {@code content_posts} 表名 ——
 *       对齐 story 4.1 时只换那一个实现；</li>
 *   <li>标识符与注释不出现 {@code transfer}（PawCoin 不可转让，§5：守卫可能扩面到钱包以外）；</li>
 *   <li>不引用 ② 的 {@code ContentPetQuery} / {@code PetQueryService}（本分支不存在，也不许自造）。</li>
 * </ol>
 */
class GiftModuleBoundaryGuardTest {

    private static final Path GIFT = Path.of("src/main/java/com/tailtopia/gift");
    private static final String DIRECTORY_IMPL = "SinglePetGiftPetDirectory.java";

    private static List<Path> javaFiles() throws IOException {
        try (Stream<Path> s = Files.walk(GIFT)) {
            return s.filter(p -> p.toString().endsWith(".java")).toList();
        }
    }

    private static String read(Path p) {
        try {
            return Files.readString(p, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void scansTheRealModule() throws IOException {
        assertThat(javaFiles()).extracting(p -> p.getFileName().toString())
                .contains(DIRECTORY_IMPL, "GiftSendService.java", "GiftDeliveryService.java", "GiftPaidHandler.java");
    }

    @Test
    void doesNotImportOtherModulesRepositories() throws IOException {
        List<String> offenders = new ArrayList<>();
        for (Path p : javaFiles()) {
            String src = read(p);
            for (String pkg : List.of("com.tailtopia.pay.repository", "com.tailtopia.profile.repository",
                    "com.tailtopia.content.repository")) {
                if (src.contains("import " + pkg)) {
                    offenders.add(p.getFileName() + " → " + pkg);
                }
            }
        }
        assertThat(offenders).as("送礼模块只经服务 / 自有端口取别的模块的数据").isEmpty();
    }

    @Test
    void onlyTheSinglePetDirectoryTouchesPetAndContentTables() throws IOException {
        List<String> offenders = new ArrayList<>();
        for (Path p : javaFiles()) {
            if (p.getFileName().toString().equals(DIRECTORY_IMPL)) {
                continue;
            }
            String src = read(p);
            if (src.contains("pet_profiles") || src.contains("content_posts")) {
                offenders.add(p.getFileName().toString());
            }
        }
        assertThat(offenders).as("取宠物只经 GiftPetDirectory（对齐 4.1 只换实现）").isEmpty();
    }

    @Test
    void noTransferWordingAnywhere() throws IOException {
        List<String> offenders = new ArrayList<>();
        for (Path p : javaFiles()) {
            if (read(p).toLowerCase(Locale.ROOT).contains("transfer")) {
                offenders.add(p.getFileName().toString());
            }
        }
        assertThat(offenders).as("🔴 PawCoin 不可转让：送礼模块标识符与注释避开该字样（§5）").isEmpty();
    }

    @Test
    void doesNotReferenceMultiPetLineInterfaces() throws IOException {
        List<String> offenders = new ArrayList<>();
        for (Path p : javaFiles()) {
            String src = read(p);
            if (src.contains("ContentPetQuery") || src.contains("PetQueryService")) {
                offenders.add(p.getFileName().toString());
            }
        }
        assertThat(offenders).as("② 的接口本分支不存在，禁止引用或自造（P-3）").isEmpty();
    }
}
