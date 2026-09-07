package com.tailtopia.shared.boundary;

import static org.assertj.core.api.Assertions.assertThat;

import com.tailtopia.pay.service.PawCoinWalletService;
import com.tailtopia.profile.domain.HealthRecordType;
import com.tailtopia.shop.domain.ProductCategory;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 🔴🔴 L0 CI 长期看守：问诊与商品的边界（Story 9.1，FR-110 + FR-100A 规则 6，<b>安全攸关</b>）。
 *
 * <p><b>decision-log N-3：</b>《战略决策快照》「靠什么赢」第一条是 AI 问诊与本地信任。
 * <b>此约束优先于任何转化率优化</b> —— 问诊一旦被感知为销售前端，护城河即失效，
 * 其损失远大于电商的短期转化收益。
 *
 * <p>🔴 <b>验收形态必须是「能力缺席」，不是「权限拦住了」。</b>
 * 权限可以被改、可以被绕；一个不存在的 import、一个不存在的方法参数，改不了也绕不过。
 * 上线后转化压力会持续存在，所以这些检查<b>入 CI 长期看守</b>：任何 PR 引入违反即失败。
 *
 * <p>⚠️ 本类是纯文件扫描，无 Spring、无 DB —— 它必须能在最快的那一档流水线里跑。
 *
 * <p><b>为什么两个 boundary 类落在 {@code shared} 而不是 {@code shop}：</b>
 * 它们是问诊侧要用的东西（消息渲染前过滤、结论页品类跳转）。
 * 若放在 {@code shop} 包下，问诊侧一用就得 {@code import com.tailtopia.shop.*} ——
 * 那正是本类第一条断言要禁的形状。
 * 🔴 <b>{@code shared.boundary} 是问诊与商品之间唯一的、窄到只能过枚举的桥。</b>
 */
class ConsultShopBoundaryTest {

    private static final Path MAIN = Path.of("src", "main", "java");
    private static final Path WALLET_SERVICE =
            MAIN.resolve("com/tailtopia/pay/service/PawCoinWalletService.java");

    /** {@code public|private ... 返回类型 方法名(形参列表)}，只用于形状检查，不求语法级严谨。 */
    private static final Pattern METHOD_DECL = Pattern.compile(
            "(?:public|protected|private)\\s+(?:static\\s+)?[\\w<>,.\\[\\] ]+?\\s+(\\w+)\\s*\\(([^)]*)\\)");

    // ---------- FR-110 ①：兽医端不 import 任何 shop 包 ----------

    @Test
    @DisplayName("🔴🔴 FR-110：vet/ 与 consult/ 下不存在任何对 shop 包的引用（能力缺席）")
    void vetAndConsultDoNotImportShop() {
        List<String> offenders = new ArrayList<>();
        for (String pkg : List.of("vet", "consult", "triage")) {
            forEachJava(MAIN.resolve("com").resolve("tailtopia").resolve(pkg), (path, src) -> {
                for (String line : src.split("\n")) {
                    String t = line.strip();
                    if (t.startsWith("import ") && t.contains("com.tailtopia.shop")) {
                        offenders.add(path + " → " + t);
                    }
                }
            });
        }
        assertThat(offenders)
                .as("🔴 兽医/问诊模块引用了 shop 包 —— 问诊一旦具备推荐商品的能力，"
                        + "它就会被感知为销售前端，而那是我们赖以取胜的东西")
                .isEmpty();
    }

    @Test
    @DisplayName("🔴 FR-110：兽医端不存在商品搜索 / 商品选择器 / 商品链接插入组件")
    void vetHasNoProductPickerCapability() {
        List<String> offenders = new ArrayList<>();
        // 逐个点名可疑符号，而不是模糊匹配 "product" —— 后者会误伤（如 productionMode）
        List<String> banned = List.of("ShopProduct", "ShopSku", "SkuPicker", "ProductPicker",
                "ProductSearch", "insertProductLink", "shopProductToken");
        for (String pkg : List.of("vet", "consult", "triage")) {
            forEachJava(MAIN.resolve("com").resolve("tailtopia").resolve(pkg), (path, src) -> {
                String code = stripComments(src);
                for (String bad : banned) {
                    if (code.contains(bad)) {
                        offenders.add(path + " → " + bad);
                    }
                }
            });
        }
        assertThat(offenders).as("🔴 兽医端出现了商品选择能力的痕迹").isEmpty();
    }

    // ---------- FR-110 ②：商品链接不渲染为可点击卡片 ----------

    @Test
    @DisplayName("🔴 手填的商品链接被识别并降级为纯文本（不删内容，只去掉「点进去就能买」）")
    void shopLinksAreNeutralized() {
        for (String text : List.of(
                "coba beli ini https://tailtopia.id/shop/products/abc123",
                "lihat /shop/products/abc123 ya",
                "tailtopia://shop/products/abc123",
                "buka https://x.test/api/v1/shop/products/abc123")) {
            assertThat(ShopLinkPolicy.containsShopLink(text)).as("没识别出：%s", text).isTrue();
            String out = ShopLinkPolicy.neutralize(text);
            assertThat(ShopLinkPolicy.containsShopLink(out))
                    .as("降级后仍能被识别成商品链接：%s", out).isFalse();
            // 🔴 不删内容：兽医写的其余字还在
            assertThat(out).isNotBlank();
        }
    }

    @Test
    @DisplayName("普通文本与非商品链接不受影响（过度过滤会让兽医以为消息发失败了）")
    void ordinaryTextIsUntouched() {
        for (String text : List.of(
                "Kasih obat cacing 1 tablet ya",
                "lihat https://tailtopia.id/articles/deworming",
                "hubungi klinik terdekat")) {
            assertThat(ShopLinkPolicy.containsShopLink(text)).isFalse();
            assertThat(ShopLinkPolicy.neutralize(text)).isEqualTo(text);
        }
    }

    // ---------- FR-110 ③：结论页仅允许系统生成的品类跳转 ----------

    @Test
    @DisplayName("🔴 结论页的商品关联只有「记录类型 → 品类」一种形式，且兽医无法带 SKU")
    void conclusionOnlyMapsRecordTypeToCategory() {
        assertThat(TriageCategoryJump.categoryFor(HealthRecordType.DEWORM))
                .isEqualTo(ProductCategory.OBAT_VITAMIN);
        assertThat(TriageCategoryJump.categoryFor(HealthRecordType.VACCINE))
                .isEqualTo(ProductCategory.OBAT_VITAMIN);
        // 🔴 其余类型不跳 —— 每条结论都配购物入口正是 FR-110 要防的形态
        assertThat(TriageCategoryJump.categoryFor(HealthRecordType.MENSTRUATION)).isNull();
        assertThat(TriageCategoryJump.categoryFor(HealthRecordType.NEUTER)).isNull();
        assertThat(TriageCategoryJump.categoryFor(null)).isNull();

        // 🔴 能力缺席：整条路径上没有任何能携带 SKU 的入参
        for (var m : TriageCategoryJump.class.getDeclaredMethods()) {
            for (var p : m.getParameterTypes()) {
                assertThat(p)
                        .as("方法 %s 接受了 %s —— 只要有一个地方能传具体商品，约束就破了",
                                m.getName(), p.getSimpleName())
                        .isIn(HealthRecordType.class, ProductCategory.class);
            }
        }
    }

    @Test
    @DisplayName("🔴 桥本身足够窄：shared/boundary 下只有这两个类，且不引任何 shop 服务/仓储")
    void theBridgeStaysNarrow() {
        Path bridge = MAIN.resolve("com/tailtopia/shared/boundary");
        List<String> classes = new ArrayList<>();
        List<String> offenders = new ArrayList<>();
        forEachJava(bridge, (path, src) -> {
            classes.add(path.getFileName().toString());
            for (String line : src.split("\n")) {
                String t = line.strip();
                if (!t.startsWith("import ")) {
                    continue;
                }
                // 🔴 桥上只许过 shop 的【领域枚举】。一旦能引 service/repository/entity，
                // 问诊侧就能隔着这层桥拿到具体商品，桥就不是桥而是门了。
                if (t.contains("com.tailtopia.shop")
                        && !t.contains("com.tailtopia.shop.domain.ProductCategory")) {
                    offenders.add(path + " → " + t);
                }
            }
        });
        assertThat(classes)
                .as("🔴 有人往桥上加了新类 —— 这层的每一个新增都要被当成边界变更来审")
                .containsExactlyInAnyOrder("ShopLinkPolicy.java", "TriageCategoryJump.java");
        assertThat(offenders).as("🔴 桥引了 ProductCategory 之外的 shop 类型").isEmpty();
    }

    // ---------- FR-100A 规则 6：不可转让 / 无外部结算出口（S-15） ----------

    @Test
    @DisplayName("🔴🔴 规则 6：钱包服务不存在跨用户余额转移的可达路径（credit/debit 均为单一 userId 作用域）")
    void walletHasNoCrossUserTransferPath() {
        // ⚠️ 走源码而不是反射：本工程没开 -parameters，反射拿到的形参名是 arg0，
        // 「有没有第二个 userId」这种形状检查在字节码上根本看不出来。
        String code = stripComments(read(WALLET_SERVICE));

        for (String bad : List.of("transfer", "gift", "移交", "赠予")) {
            assertThat(code.toLowerCase())
                    .as("🔴 钱包服务里出现了 \"%s\" —— 不可转让是 PawCoin 闭环的三条特征之一", bad)
                    .doesNotContain(bad.toLowerCase());
        }

        // 🔴 一个方法签名里出现两个 userId 形参 = 存在「从谁转给谁」的形状
        Matcher m = METHOD_DECL.matcher(code);
        while (m.find()) {
            String method = m.group(1);
            String params = m.group(2);
            long userParams = java.util.Arrays.stream(params.split(","))
                    .filter(p -> p.toLowerCase().contains("userid")).count();
            assertThat(userParams)
                    .as("方法 %s(%s) 有 %d 个 userId 形参 —— 跨用户转移的形状",
                            method, params, userParams)
                    .isLessThanOrEqualTo(1);
        }

        // 反射侧只做一件源码做不到的事：确认没有别处偷偷加了叫得可疑的公开方法
        for (var jm : PawCoinWalletService.class.getDeclaredMethods()) {
            assertThat(jm.getName().toLowerCase())
                    .as("方法 %s 的命名暗示存在跨用户转移", jm.getName())
                    .doesNotContain("transfer").doesNotContain("gift");
        }
    }

    @Test
    @DisplayName("🔴🔴 规则 6（S-15 修正）：不存在任何平台外部结算出口 —— 包含 PPN 等税费在内")
    void noExternalSettlementOutlet() {
        // S-15 已修正原措辞：原文「PPN【之外的】任何外部结算」字面把 PPN 排除在禁止之外，
        // 等于允许用无现金垫底的赠币缴税。这里按修正后的口径断言：一个出口都不许有。
        String code = stripComments(read(WALLET_SERVICE));
        for (String bad : List.of("payout", "withdraw", "cashOut", "remit", "settleExternal",
                "ppn", "tax")) {
            assertThat(code.toLowerCase())
                    .as("🔴 钱包服务里出现了 \"%s\" —— PawCoin 是封闭币，"
                            + "任何外部结算出口都会打破「不可提现 / 不可转让 / 仅限平台自营商品」"
                            + "三条闭环特征，而这三条是 DEP-7 合规确认的前提", bad)
                    .doesNotContain(bad);
        }
    }

    @Test
    @DisplayName("🔴 电商侧也没有把 PawCoin 折成现金的路径（Epic 5 的退款执行同受此约束）")
    void shopSideHasNoCoinToCashPath() {
        List<String> offenders = new ArrayList<>();
        forEachJava(MAIN.resolve("com/tailtopia/shop"), (path, src) -> {
            String code = stripComments(src);
            if (code.contains("wallet.debit(") && !path.toString().contains("CheckoutService")) {
                // 只有结算时扣币是合法的 debit；其余任何 debit 都要解释清楚
                offenders.add(path.toString());
            }
        });
        assertThat(offenders)
                .as("🔴 shop 模块里出现了结算之外的钱包扣减 —— 那是把币拿走的第一步")
                .isEmpty();
    }

    // ---------- 内部 ----------

    private interface JavaVisitor {
        void visit(Path path, String source);
    }

    private static void forEachJava(Path root, JavaVisitor visitor) {
        if (!Files.isDirectory(root)) {
            return;
        }
        try (Stream<Path> files = Files.walk(root)) {
            files.filter(p -> p.getFileName().toString().endsWith(".java"))
                    .forEach(p -> visitor.visit(p, read(p)));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String read(Path p) {
        try {
            return Files.readString(p, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** 🔴 去注释后再断言：注释里写「不做 payout」不该让检查变红。 */
    private static String stripComments(String src) {
        return src.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("//[^\n]*", "");
    }
}
