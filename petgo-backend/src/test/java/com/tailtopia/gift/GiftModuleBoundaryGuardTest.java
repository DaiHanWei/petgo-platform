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
 *   <li>送礼包内一律不出现 {@code pet_profiles} 字面量，不出现读 {@code content_posts.pet_id} 的写法；
 *       {@code content_posts} 只允许多宠实现 {@code MultiPetGiftPetDirectory} 判帖子可送礼（只读状态列，Story 4.1 · AC1.2）；</li>
 *   <li>标识符与注释不出现 {@code transfer}（PawCoin 不可转让，§5：守卫可能扩面到钱包以外）；</li>
 *   <li>② 的 {@code ContentPetQuery} / {@code PetQueryService} 只允许 {@code MultiPetGiftPetDirectory} 引用；</li>
 *   <li>主代码里 {@code GiftPetDirectory} 的实现类恰好 1 个（不 {@code @Primary} 双实现并存，Story 4.1 · AC1.2）。</li>
 * </ol>
 */
class GiftModuleBoundaryGuardTest {

    private static final Path GIFT = Path.of("src/main/java/com/tailtopia/gift");
    private static final String DIRECTORY_IMPL = "MultiPetGiftPetDirectory.java";

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
    void noPetTableAndContentPostsOnlyInTheDirectory() throws IOException {
        List<String> offenders = new ArrayList<>();
        for (Path p : javaFiles()) {
            String name = p.getFileName().toString();
            String src = read(p);
            if (src.contains("pet_profiles")) {
                offenders.add(name + " → pet_profiles");
            }
            if (src.contains("content_posts")) {
                if (!name.equals(DIRECTORY_IMPL)) {
                    offenders.add(name + " → content_posts");
                } else if (src.contains("pet_id")) {
                    offenders.add(name + " → content_posts.pet_id（关联宠物只经 ContentPetQuery）");
                }
            }
        }
        assertThat(offenders).as("取宠物只经 GiftPetDirectory；多宠实现只读帖子状态列").isEmpty();
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
            if (p.getFileName().toString().equals(DIRECTORY_IMPL)) {
                continue;
            }
            if (src.contains("ContentPetQuery") || src.contains("PetQueryService")) {
                offenders.add(p.getFileName().toString());
            }
        }
        assertThat(offenders).as("② 的读口只经 GiftPetDirectory 的多宠实现（AD-5）").isEmpty();
    }

    /** Story 4.1 · AC1.2：主代码里 {@code GiftPetDirectory} 恰好一个实现（单宠过渡实现已删，不并存）。 */
    @Test
    void exactlyOneGiftPetDirectoryImplementation() throws IOException {
        List<String> impls = new ArrayList<>();
        try (Stream<Path> s = Files.walk(Path.of("src/main/java"))) {
            for (Path p : s.filter(f -> f.toString().endsWith(".java")).toList()) {
                if (read(p).matches("(?s).*\\bimplements\\s+([\\w.]+\\s*,\\s*)*(\\w+\\.)*GiftPetDirectory\\b.*")) {
                    impls.add(p.getFileName().toString());
                }
            }
        }
        assertThat(impls).containsExactly(DIRECTORY_IMPL);
    }

    /**
     * Story 1.2 · AC5.4：通知模块提供方无关 —— notify 任何源码不 import 送礼模块；本 story 动到的通知类与读端口包
     * 也不 import profile（宠物名只经 {@code notify.port.NotificationPetNames}）。
     */
    @Test
    void notifyModuleDoesNotImportGiftAndPetNamesGoThroughPort() throws IOException {
        Path notify = Path.of("src/main/java/com/tailtopia/notify");
        List<String> offenders = new ArrayList<>();
        try (Stream<Path> s = Files.walk(notify)) {
            for (Path p : s.filter(f -> f.toString().endsWith(".java")).toList()) {
                String src = read(p);
                if (src.contains("com.tailtopia.gift.")) {
                    offenders.add(p.getFileName() + " → gift");
                }
                String name = p.getFileName().toString();
                boolean touched = p.toString().contains("/notify/port/")
                        || List.of("NotificationService.java", "NotificationCenterService.java",
                                "InteractionCopyRenderer.java").contains(name);
                if (touched && src.contains("com.tailtopia.profile.")) {
                    offenders.add(name + " → profile");
                }
            }
        }
        assertThat(offenders).as("notify 不依赖送礼 / 宠物档案的类（AC5.4）").isEmpty();
    }

    /** Story 1.2：退回只调钱包 {@code credit}（退给送礼人自己），送礼模块不出现 {@code debit} 之外的钱包写入口。 */
    @Test
    void refundOnlyCreditsTheSenderThroughWallet() throws IOException {
        String src = read(GIFT.resolve("service/GiftRefundService.java"));
        assertThat(src).contains("wallet.credit(order.getSenderId()").doesNotContain("getOwnerId()");
    }
}
