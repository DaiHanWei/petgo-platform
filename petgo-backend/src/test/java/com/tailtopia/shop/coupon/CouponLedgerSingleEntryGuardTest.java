package com.tailtopia.shop.coupon;

import static org.assertj.core.api.Assertions.assertThat;

import com.tailtopia.shop.coupon.domain.UserCoupon;
import com.tailtopia.shop.coupon.repository.UserCouponRepository;
import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.hibernate.annotations.Immutable;
import org.junit.jupiter.api.Test;
import org.springframework.data.repository.CrudRepository;

/**
 * L0：v1.4.2 shop Story 2.1 AC6（P-2）—— 用户券状态单入口守卫。
 *
 * <ol>
 *   <li>写用户券表的 SQL 只允许出现在 {@code UserCouponLedgerDao.java}；</li>
 *   <li>{@code UserCouponLedgerDao} 只允许被 {@code CouponLedgerService.java} import；</li>
 *   <li>{@code UserCoupon} 无 public {@code set*}、带 {@link Immutable}；{@code UserCouponRepository} 不是 {@link CrudRepository}。</li>
 * </ol>
 */
class CouponLedgerSingleEntryGuardTest {

    private static final Path SRC = Path.of("src/main/java");
    private static final Pattern WRITE_SQL =
            Pattern.compile("(?is)\\b(insert\\s+into|update|delete\\s+from)\\s+user_coupons\\b");
    private static final String DAO_IMPORT = "import com.tailtopia.shop.coupon.repository.UserCouponLedgerDao";

    private static List<Path> javaFiles() throws IOException {
        try (Stream<Path> s = Files.walk(SRC)) {
            return s.filter(p -> p.toString().endsWith(".java")).toList();
        }
    }

    private static String read(Path p) {
        try {
            return Files.readString(p, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(p.toString(), e);
        }
    }

    @Test
    void writeSqlOnlyInLedgerDao() throws IOException {
        List<String> offenders = javaFiles().stream()
                .filter(p -> !p.getFileName().toString().equals("UserCouponLedgerDao.java"))
                .filter(p -> WRITE_SQL.matcher(read(p)).find())
                .map(Path::toString).toList();
        assertThat(offenders).as("用户券表写语句只允许出现在 UserCouponLedgerDao.java").isEmpty();
        // 自检：DAO 里确实有写语句（防止正则失效导致守卫空转）
        Path dao = SRC.resolve("com/tailtopia/shop/coupon/repository/UserCouponLedgerDao.java");
        assertThat(WRITE_SQL.matcher(read(dao)).find()).isTrue();
    }

    @Test
    void ledgerDaoOnlyInjectedIntoLedgerService() throws IOException {
        List<String> offenders = javaFiles().stream()
                .filter(p -> !p.getFileName().toString().equals("CouponLedgerService.java"))
                .filter(p -> read(p).contains(DAO_IMPORT))
                .map(Path::toString).toList();
        assertThat(offenders).as("UserCouponLedgerDao 只允许被 CouponLedgerService 引用").isEmpty();
        // 同包（repository 包内）不需要 import 也能引用：同包其它文件也不得出现该类型名
        Path repoDir = SRC.resolve("com/tailtopia/shop/coupon/repository");
        try (Stream<Path> s = Files.list(repoDir)) {
            List<String> samePackage = s.filter(p -> !p.getFileName().toString().equals("UserCouponLedgerDao.java"))
                    .filter(p -> read(p).contains("UserCouponLedgerDao"))
                    .map(Path::toString).toList();
            assertThat(samePackage).as("repository 包内其它文件不得引用 UserCouponLedgerDao").isEmpty();
        }
    }

    @Test
    void userCouponIsReadOnly() {
        assertThat(UserCoupon.class.isAnnotationPresent(Immutable.class)).as("UserCoupon 须带 @Immutable").isTrue();
        List<String> setters = Arrays.stream(UserCoupon.class.getMethods())
                .filter(m -> Modifier.isPublic(m.getModifiers()))
                .map(Method::getName)
                .filter(n -> n.startsWith("set"))
                .toList();
        assertThat(setters).as("UserCoupon 不得有 public set*").isEmpty();
        assertThat(CrudRepository.class.isAssignableFrom(UserCouponRepository.class))
                .as("UserCouponRepository 不得继承 CrudRepository").isFalse();
    }
}
