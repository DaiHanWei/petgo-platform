package com.tailtopia.purchase;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * V1.3.2 Story 3.1 · AC5.6 · L0 源码守卫：三个一次性解锁 purpose 的 {@code PaymentIntentPaidEvent} 只有一个消费者。
 *
 * <p>埋点 / 订单等下游一律订阅 {@code KeepsakeUnlockedEvent}；另起一个到账监听会让「到账 → 发放」出现两条路径。
 * 另：purchase 包不得依赖任何 SKU 模块（tailsonality / passport）。
 */
class KeepsakeSingleConsumerSourceGuardTest {

    private static final Path MAIN = Path.of("src/main/java/com/tailtopia");
    private static final Pattern KEEPSAKE = Pattern.compile("\\b(TAILSONALITY|PASSPORT_SNAP|BOARDING_PASS|KeepsakeSku)\\b");

    private static List<Path> javaFiles(Path root) throws IOException {
        try (Stream<Path> s = Files.walk(root)) {
            return s.filter(p -> p.toString().endsWith(".java")).toList();
        }
    }

    @Test
    void onlyKeepsakePaidHandlerConsumesPaidEventsForKeepsakePurposes() throws IOException {
        List<String> consumers = new java.util.ArrayList<>();
        for (Path p : javaFiles(MAIN)) {
            String src = Files.readString(p, StandardCharsets.UTF_8);
            boolean listensPaid = Pattern.compile("\\(\\s*PaymentIntentPaidEvent\\s+\\w+\\s*\\)").matcher(src).find();
            if (listensPaid && KEEPSAKE.matcher(src).find()) {
                consumers.add(p.getFileName().toString());
            }
        }
        assertThat(consumers).containsExactly("KeepsakePaidHandler.java");
    }

    @Test
    void purchasePackageDoesNotDependOnSkuModules() throws IOException {
        for (Path p : javaFiles(MAIN.resolve("purchase"))) {
            String src = Files.readString(p, StandardCharsets.UTF_8);
            assertThat(src).as(p.toString()).doesNotContain("import com.tailtopia.tailsonality")
                    .doesNotContain("import com.tailtopia.passport").doesNotContain("import com.tailtopia.profile");
        }
    }
}
