package com.tailtopia.shared.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 🔒 电商用户侧端点必须显式限定 {@code hasRole("USER")}（v1.3.0 shop-v2 复审 #1）。
 *
 * <p><b>这条守的是什么</b>：{@code /api/v1/me/**} 下的电商 controller 一律用
 * {@code jwt.sub} 当 {@code users.id} 解，而<b>兽医 token 的 {@code sub} 是 vetId</b>，
 * 与 {@code users.id} 是两个独立命名空间、编号大量碰撞。只要某条路径漏在
 * {@link SecurityConfig} 的 {@code hasRole("USER")} 名单外，它就会落到
 * {@code anyRequest().authenticated()} —— 兽医 token 照样放行，于是：
 * <ul>
 *   <li>读到同号用户的<b>收件人姓名 / 电话 / 详细地址</b>（收货地址族）；</li>
 *   <li>改其购物车、以其名义下单（结算族）。</li>
 * </ul>
 * 这正是 {@code SecurityConfig} 里 blocked-users 那段注释当年写下的同一个坑，
 * 只是名单一直没扩到电商族。
 *
 * <p><b>为什么扫源码而不是发请求</b>：真发请求要起 Spring 上下文与数据库（L1），
 * 而这条护栏真正的失效方式是「<b>有人加了新端点、忘了往名单里登记</b>」——
 * 那种回归在 L0 就该被拦下，不该等到集成测试环境齐了才发现。本仓已有同类扫源码的
 * 守门测试（{@code AdminPermissionWiringTest}），此处沿用该范式。
 */
class ShopUserEndpointsRoleGuardTest {

    private static final Path SECURITY_CONFIG =
            Path.of("src", "main", "java", "com", "tailtopia", "shared", "security", "SecurityConfig.java");
    private static final Path SHOP_SRC =
            Path.of("src", "main", "java", "com", "tailtopia", "shop");

    /** 类级 {@code @RequestMapping("/api/v1/me...")}。 */
    private static final Pattern CLASS_MAPPING =
            Pattern.compile("@RequestMapping\\(\"(/api/v1/me[^\"]*)\"\\)");
    /** 方法级 {@code @GetMapping("/xxx")} 等（无参的 {@code @PostMapping} 不带路径，忽略）。 */
    private static final Pattern METHOD_MAPPING =
            Pattern.compile("@(?:Get|Post|Put|Patch|Delete)Mapping\\(\"(/[^\"]*)\"");

    @Test
    @DisplayName("🎯 每个 /api/v1/me 下的电商端点，其路径前缀都在 SecurityConfig 的 hasRole(\"USER\") 名单里")
    void everyShopUserEndpointIsRoleGuarded() {
        String userRoleMatchers = userRoleBlockOf(read(SECURITY_CONFIG));

        Set<String> uncovered = new TreeSet<>();
        for (String path : shopUserEndpointPaths()) {
            if (!isCovered(path, userRoleMatchers)) {
                uncovered.add(path);
            }
        }

        assertThat(uncovered)
                .as("""
                        以下电商用户侧端点没有被 SecurityConfig 的 hasRole("USER") 名单覆盖，
                        等于兽医 token 也能访问（controller 会把 vetId 当 users.id 用）。
                        修法：把路径补进 SecurityConfig 里「电商用户侧全族」那个 requestMatchers。
                        🎯 变异靶子：从该 requestMatchers 里删掉任意一条路径，本测试必须变红。""")
                .isEmpty();
    }

    @Test
    @DisplayName("自检：真的扫到了端点（扫不到会让上一条假绿）")
    void scannerActuallyFindsEndpoints() {
        // 没有这条，扫描器哪天被改坏 / 目录挪走，everyShopUserEndpointIsRoleGuarded
        // 会因为「一个端点都没扫到」而永远绿 —— 那是最坏的一种假绿。
        assertThat(shopUserEndpointPaths())
                .as("扫描器应当扫到电商用户侧端点；一个都没扫到说明扫描逻辑失效了")
                .hasSizeGreaterThanOrEqualTo(10)
                .anyMatch(p -> p.startsWith("/api/v1/me/cart"))
                .anyMatch(p -> p.startsWith("/api/v1/me/shop-orders"));
    }

    /**
     * 名单是否覆盖该路径：命中同名条目，或命中其 {@code /**} 前缀条目。
     *
     * <p>逐段回退而不是做字符串 contains —— 后者会让 {@code "/api/v1/me/cart"} 被
     * 注释里随便一句提到 cart 的话“覆盖”掉。
     */
    private boolean isCovered(String path, String matchers) {
        if (matchers.contains('"' + path + '"')) {
            return true;
        }
        for (String prefix = path; prefix.contains("/"); prefix = prefix.substring(0, prefix.lastIndexOf('/'))) {
            if (matchers.contains('"' + prefix + "/**\"")) {
                return true;
            }
        }
        return false;
    }

    /**
     * 只取 SecurityConfig 里 {@code hasRole("USER")} 的那些 requestMatchers 段。
     *
     * <p>🔴 不能整文件 contains：{@code /api/v1/vet/**} 走的是 {@code hasRole("VET")}，
     * 还有 permitAll 段；整文件比对会把「登记在别的角色下」误判成已覆盖。
     */
    private String userRoleBlockOf(String source) {
        StringBuilder out = new StringBuilder();
        Matcher m = Pattern.compile("\\.requestMatchers\\((.*?)\\)\\s*\\.hasRole\\(\"USER\"\\)", Pattern.DOTALL)
                .matcher(source);
        while (m.find()) {
            out.append(m.group(1)).append('\n');
        }
        assertThat(out.length())
                .as("SecurityConfig 里应当存在 hasRole(\"USER\") 的 requestMatchers 段")
                .isGreaterThan(0);
        return out.toString();
    }

    /** 扫 shop 包下所有 controller，拼出 {@code /api/v1/me} 开头的完整路径。 */
    private Set<String> shopUserEndpointPaths() {
        Set<String> paths = new TreeSet<>();
        try (Stream<Path> files = Files.walk(SHOP_SRC)) {
            files.filter(p -> p.toString().endsWith("Controller.java")).forEach(p -> {
                String src = read(p);
                Matcher cm = CLASS_MAPPING.matcher(src);
                if (!cm.find()) {
                    return;     // 不是 /api/v1/me 族（公开目录、admin 等），与本测试无关
                }
                String base = cm.group(1);
                Matcher mm = METHOD_MAPPING.matcher(src);
                boolean anyMethodPath = false;
                while (mm.find()) {
                    anyMethodPath = true;
                    paths.add(normalize(base + mm.group(1)));
                }
                if (!anyMethodPath) {
                    paths.add(normalize(base));
                }
            });
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return paths;
    }

    /** 去掉路径变量段：名单登记的是前缀，{@code {token}} 具体是什么与授权无关。 */
    private String normalize(String path) {
        int brace = path.indexOf('{');
        String cut = brace < 0 ? path : path.substring(0, brace);
        return cut.endsWith("/") && cut.length() > 1 ? cut.substring(0, cut.length() - 1) : cut;
    }

    private String read(Path p) {
        try {
            return Files.readString(p, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
