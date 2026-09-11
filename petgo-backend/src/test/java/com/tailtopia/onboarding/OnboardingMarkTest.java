package com.tailtopia.onboarding;

import static org.assertj.core.api.Assertions.assertThat;

import com.tailtopia.onboarding.domain.OnboardingMarkKey;
import com.tailtopia.onboarding.domain.UserOnboardingMark;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * V1.3.0 批次 A · Story 5.4（L0）：一次性引导标记键值表（FR-65 · AD-A21）。
 *
 * <p>蒙层长什么样是 L2、置位幂等与注销级联真跑要 DB（L1）。这里钉住 L0 能钉的：
 * **键值形态**、**无 PII**、**一个引导一个键**（批次 C 不许共用）、以及注销级联已接上。
 */
class OnboardingMarkTest {

    private static String migration() {
        try (var files = Files.list(Path.of("src", "main", "resources", "db", "migration"))) {
            List<Path> hits = files
                    .filter(p -> p.getFileName().toString().contains("user_onboarding_marks"))
                    .toList();
            assertThat(hits).as("应恰好有一支引导标记迁移").hasSize(1);
            return Files.readString(hits.get(0), StandardCharsets.UTF_8).toLowerCase(Locale.ROOT);
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }

    /** DDL 部分（剥掉 {@code --} 注释）—— 注释里本来就会提到被禁的东西。 */
    private static String ddl() {
        return migration().lines()
                .map(line -> {
                    int c = line.indexOf("--");
                    return c >= 0 ? line.substring(0, c) : line;
                })
                .collect(java.util.stream.Collectors.joining("\n"));
    }

    @Nested
    @DisplayName("AC1 键值表：形态、唯一、无 PII、时间戳迁移")
    class TableShape {

        /**
         * 🔴 **键值形态**，不是每个引导一列。
         * 每来一个一次性引导就往 users 加一个 boolean 列 —— 这张表存在的唯一理由就是挡住它。
         */
        @Test
        void isKeyValueShaped() {
            String flat = ddl().replaceAll("\\s+", " ");

            assertThat(flat).contains("create table user_onboarding_marks");
            assertThat(flat).contains("user_id bigint not null");
            assertThat(flat).contains("mark_key varchar(64) not null");
            assertThat(flat).contains("first_seen_at timestamptz not null");
        }

        /** 🛡 置位的幂等落在唯一约束上，不靠「先查再插」。 */
        @Test
        void userAndKeyAreUniqueTogether() {
            assertThat(ddl().replaceAll("\\s+", " ")).contains("unique (user_id, mark_key)");
        }

        /**
         * 🔴 AC1：表里**不含任何 PII**。只有 user_id、一个内部常量键名、一个时刻 ——
         * 键名是我们自己定义的，不是用户输入，也不描述用户。
         */
        @Test
        void carriesNoPii() {
            String flat = ddl();
            for (String banned : List.of("nickname", "email", "phone", "avatar",
                    "device", "locale")) {
                assertThat(flat).as("引导标记表不该有 %s", banned).doesNotContain(banned);
            }
            // 实体侧同样只有这四样。
            List<String> fields = Arrays.stream(UserOnboardingMark.class.getDeclaredFields())
                    .map(java.lang.reflect.Field::getName).toList();
            assertThat(fields).containsExactlyInAnyOrder("id", "userId", "markKey", "firstSeenAt");
        }

        @Test
        void migrationFileNameIsTimestamped() {
            try (var files = Files.list(Path.of("src", "main", "resources", "db", "migration"))) {
                Path f = files.filter(p -> p.getFileName().toString()
                        .contains("user_onboarding_marks")).findFirst().orElseThrow();
                assertThat(f.getFileName().toString()).matches(
                        "^V20\\d{2}(0[1-9]|1[0-2])(0[1-9]|[12]\\d|3[01])_"
                                + "([01]\\d|2[0-3])[0-5]\\d__[a-z0-9_]+\\.sql$");
            } catch (IOException e) {
                throw new AssertionError(e);
            }
        }
    }

    @Nested
    @DisplayName("AC6 🔴 键的隔离：本批次只落一个键")
    class KeyIsolation {

        /**
         * 🔴 本批次**只有 KTP 位置迁移这一个键**。
         * 批次 C 的 Tailsonality 入口引导**必须另起一个键** —— PRD 明确那是两次独立触发，
         * 共用一个键会让看过第一次的人再也收不到第二次。
         *
         * <p>⚠️ 这条会在批次 C 加键时变红，那是**有意的**：加键的人必须在这里
         * 写下「我加的是第二个键，不是复用第一个」。
         */
        @Test
        void onlyOneKeyInThisBatch() {
            assertThat(OnboardingMarkKey.values()).hasSize(1);
            assertThat(OnboardingMarkKey.KTP_MOVED.wire()).isEqualTo("ktp_moved");
        }

        /** 键名要说清是哪个引导，不能是个万能筐 —— 万能筐就是"共用一个键"的入口。 */
        @Test
        void keyIsNotAGenericCatchAll() {
            String wire = OnboardingMarkKey.KTP_MOVED.wire();
            for (String banned : List.of("onboarding", "intro", "tour", "coachmark")) {
                assertThat(wire).doesNotContain(banned);
            }
        }

        /** 🔴 未登记的键一律拒 —— 开放任意字符串等于让客户端往表里写任何东西。 */
        @Test
        void unknownWireIsRejected() {
            assertThat(OnboardingMarkKey.fromWire("ktp_moved")).contains(OnboardingMarkKey.KTP_MOVED);
            assertThat(OnboardingMarkKey.fromWire("tailsonality_intro")).isEmpty();
            assertThat(OnboardingMarkKey.fromWire("")).isEmpty();
        }
    }

    @Nested
    @DisplayName("AC7 注销级联（安全攸关）")
    class DeletionCascade {

        private String orchestratorSource() {
            try {
                return Files.readString(Path.of("src/main/java/com/tailtopia/account/service/"
                        + "AccountDeletionService.java"), StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new AssertionError(e);
            }
        }

        /**
         * 🔴 新增一张带用户外键的表就要同时接上注销级联。
         * 漏接的表会在注销后**留着指向一个已不存在的人的行**，
         * 而这类遗漏没有任何报错会提醒你 —— 所以用一条测试来提醒。
         */
        @Test
        void marksAreDeletedWithTheAccount() {
            assertThat(orchestratorSource())
                    .contains("onboardingMarkDeletion.deleteByUserId(userId)");
        }

        /** 必须发生在删 user 行**之前** —— 之后就没有 user_id 可识别了。 */
        @Test
        void deletionHappensBeforeTheUserRowIsRemoved() {
            String src = orchestratorSource();
            int marks = src.indexOf("onboardingMarkDeletion.deleteByUserId(userId)");
            int authLast = src.indexOf("media = media.merge(authDeletion.deleteByUserId(userId))");

            assertThat(marks).isNotNegative();
            assertThat(authLast).as("auth 最后删（删掉 user 行）").isGreaterThan(marks);
        }

        @Test
        void hasUserForeignKey() {
            assertThat(ddl()).contains("references users (id)");
        }
    }
}
