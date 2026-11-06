package com.tailtopia.shop.order.pricing;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * L0 守卫：计价器包是纯函数（v1.4.2 Story 1.2，D-7 / P-1 / m6）。
 *
 * <p>扫描 {@code shop/order/pricing/} 下全部源文件，禁止仓库、注入、读时钟、JPA，
 * 以及对券（2.1）/ 物流（1.1）领域类的依赖。
 */
class ShopPricingPurityTest {

    private static final Path PRICING =
            Path.of("src", "main", "java", "com", "tailtopia", "shop", "order", "pricing");

    private static final List<String> FORBIDDEN = List.of(
            "Repository",
            "@Autowired",
            "Instant.now(",
            "LocalDate.now(",
            "LocalDateTime.now(",
            "Clock",
            "import com.tailtopia.shop.coupon",
            "import com.tailtopia.shop.logistics",
            "jakarta.persistence");

    @Test
    @DisplayName("pricing 包源文件不含任何 IO / 时钟 / 券与物流领域依赖")
    void pricingPackageIsPure() throws IOException {
        assertThat(Files.isDirectory(PRICING)).as("pricing 包存在").isTrue();
        List<Path> files;
        try (Stream<Path> s = Files.walk(PRICING)) {
            files = s.filter(p -> p.toString().endsWith(".java")).toList();
        }
        assertThat(files).as("至少包含计价器本体")
                .anyMatch(p -> p.getFileName().toString().equals("ShopPricingCalculator.java"));
        for (Path p : files) {
            String src = Files.readString(p, StandardCharsets.UTF_8);
            for (String f : FORBIDDEN) {
                assertThat(src).as("%s 不得包含 %s", p.getFileName(), f).doesNotContain(f);
            }
        }
    }
}
