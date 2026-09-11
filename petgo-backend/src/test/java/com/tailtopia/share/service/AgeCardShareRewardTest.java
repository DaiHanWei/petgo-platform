package com.tailtopia.share.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.tailtopia.share.domain.AgeCardShareReward;
import com.tailtopia.share.repository.AgeCardShareRewardRepository;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * V1.3.0 批次 A · Story 5.3（L0）：年龄卡分享奖励链路（FR-65 · AD-A20 · 决策 A-8）。
 *
 * <p>本 story 含**一条资金口径的安全攸关约束**（AC3：日界按 WIB）。发放本身要 DB + Redis
 * （L1，见 story 的待验收清单），这里钉住 L0 能钉死的三件：
 * <ol>
 *   <li><b>WIB 那条边界</b> —— 06:00–07:00 那一小时必须落在"今天"，按 UTC 切就会领双份；</li>
 *   <li><b>不做档案级去重</b>（决策 A-8）—— 没有 pet_profile_id，更没有它的唯一约束；</li>
 *   <li><b>三层判断的顺序</b>与「领奖不带内容」。</li>
 * </ol>
 */
class AgeCardShareRewardTest {

    private static final Path MIGRATION_DIR = Path.of("src", "main", "resources", "db", "migration");

    private static String migrationSql(String fragment) {
        try (var files = Files.list(MIGRATION_DIR)) {
            List<Path> hits = files
                    .filter(p -> p.getFileName().toString().contains(fragment))
                    .toList();
            assertThat(hits).as("应恰好有一支 %s 迁移", fragment).hasSize(1);
            return Files.readString(hits.get(0), StandardCharsets.UTF_8).toLowerCase(Locale.ROOT);
        } catch (IOException e) {
            throw new AssertionError("读不到迁移目录 " + MIGRATION_DIR.toAbsolutePath(), e);
        }
    }

    /** 迁移文件的 DDL 部分（剥掉 {@code --} 注释）—— 注释里本来就会提到被禁的东西。 */
    private static String ddlOf(String fragment) {
        return migrationSql(fragment).lines()
                .map(line -> {
                    int c = line.indexOf("--");
                    return c >= 0 ? line.substring(0, c) : line;
                })
                .collect(java.util.stream.Collectors.joining("\n"));
    }

    @Nested
    @DisplayName("AC3 🔴 安全攸关：日界按 WIB，不按 UTC")
    class WibDayBoundary {

        /**
         * 🔴 <b>这是整条 story 最贵的一条</b>。按 UTC 切日，「今天」要到 WIB 早上 7 点才换 ——
         * 运营配「3 次/日」时，用户在 06:00–07:00 这一小时里能把昨天和今天的额度各领一遍。
         * <b>这是资金口径错误，不是体验问题。</b>
         *
         * <p>WIB = UTC+7。所以 UTC 的 23:00 已经是印尼的次日 06:00。
         */
        @Test
        void wibMorningSixToSevenBelongsToTheNewDay() {
            // 2026-09-10T23:30Z = 2026-09-11T06:30 WIB —— 印尼人眼里的"今天"是 11 号。
            Instant at = Instant.parse("2026-09-10T23:30:00Z");

            LocalDate wibDay = IdCardShareRewardService.shareDateOf(at);

            assertThat(wibDay).isEqualTo(LocalDate.of(2026, 9, 11));
            assertThat(wibDay)
                    .as("按 UTC 切会算成 9-10 —— 那一小时里用户能领两天的额度")
                    .isNotEqualTo(at.atZone(java.time.ZoneOffset.UTC).toLocalDate());
        }

        @Test
        void wibMidnightIsTheDayBoundary() {
            // 16:59:59Z = 23:59:59 WIB（还是昨天）；17:00:00Z = 次日 00:00 WIB。
            assertThat(IdCardShareRewardService.shareDateOf(Instant.parse("2026-09-10T16:59:59Z")))
                    .isEqualTo(LocalDate.of(2026, 9, 10));
            assertThat(IdCardShareRewardService.shareDateOf(Instant.parse("2026-09-10T17:00:00Z")))
                    .isEqualTo(LocalDate.of(2026, 9, 11));
        }

        /**
         * 🔴 <b>复用唯一实现，不另写换算</b>（AC3）。
         * 年龄卡这一侧的源码里不该出现第二个 {@code ZoneId.of("Asia/Jakarta")} ——
         * 两份实现迟早走歧，而走歧的那一天是钱的问题。
         */
        @Test
        void ageCardServiceDoesNotWriteItsOwnTimezoneConversion() throws IOException {
            String src = Files.readString(Path.of("src/main/java/com/tailtopia/share/service/"
                    + "AgeCardShareRewardService.java"), StandardCharsets.UTF_8);
            String code = src.lines()
                    .filter(l -> !l.trim().startsWith("*") && !l.trim().startsWith("//")
                            && !l.trim().startsWith("/*"))
                    .collect(java.util.stream.Collectors.joining("\n"));

            assertThat(code).doesNotContain("ZoneId");
            assertThat(code).doesNotContain("Asia/Jakarta");
            assertThat(code).doesNotContain("ZoneOffset");
            assertThat(code)
                    .as("必须调既有的唯一实现")
                    .contains("IdCardShareRewardService.shareDateOf(at)");
        }

        /** 账本里存的是 WIB 当地日（DATE，不是时间戳）—— 存时间戳就等于把切日推回调用方。 */
        @Test
        void ledgerStoresLocalDateNotTimestamp() {
            // 压平空白后比对：列对齐的空格数变一下不该让断言变红。
            assertThat(ddlOf("age_card_share_rewards").replaceAll("\\s+", " "))
                    .contains("share_date date not null");
        }
    }

    @Nested
    @DisplayName("AC2 🔴 专用账本表：不做档案级去重（决策 A-8）")
    class NoProfileDedup {

        /**
         * 🔴 年龄卡**没有卡实体，也不该按档案唯一**：同一只宠物隔几个月再生成是不同的分享物。
         * 加上 {@code pet_profile_id} 唯一约束 = 一个用户一辈子只能领一次，
         * 而那正是身份证渠道的语义，不是这个渠道的。
         */
        @Test
        void migrationHasNoProfileColumnAndNoProfileUnique() {
            String ddl = ddlOf("age_card_share_rewards");
            assertThat(ddl).doesNotContain("pet_profile_id");
            assertThat(ddl).doesNotContain("unique (pet_profile_id)");
        }

        @Test
        void entityHasNoProfileField() {
            List<String> fields = Arrays.stream(AgeCardShareReward.class.getDeclaredFields())
                    .map(java.lang.reflect.Field::getName)
                    .toList();
            assertThat(fields).containsExactlyInAnyOrder(
                    "id", "userId", "coins", "shareDate", "idempotencyKey", "createdAt");
            assertThat(fields).noneMatch(f -> f.toLowerCase(Locale.ROOT).contains("profile"));
        }

        /**
         * 仓库里也不许有按档案查的方法 —— 提供那样一个方法就是在邀请别人
         * 把「档案级去重」重新做出来。
         */
        @Test
        void repositoryExposesNoProfileLookup() {
            List<String> names = Arrays.stream(AgeCardShareRewardRepository.class
                    .getDeclaredMethods()).map(Method::getName).toList();

            assertThat(names).containsExactlyInAnyOrder(
                    "findByIdempotencyKey", // 去重（一次分享一行）
                    "countByUserIdAndShareDate", // 日上限
                    "deleteByUserId"); // 注销级联
            assertThat(names).noneMatch(n -> n.toLowerCase(Locale.ROOT).contains("profile"));
        }

        /** 🛡 AC8 的幂等落在唯一约束上，不靠「先查再插」（那是典型的并发双发）。 */
        @Test
        void idempotencyIsEnforcedByUniqueConstraint() {
            assertThat(ddlOf("age_card_share_rewards")).contains("unique (idempotency_key)");
        }

        @Test
        void migrationFileNamesAreTimestamped() {
            for (String fragment : List.of("age_card_share_rewards", "pawcoin_config_add_age_card_share")) {
                try (var files = Files.list(MIGRATION_DIR)) {
                    Path f = files.filter(p -> p.getFileName().toString().contains(fragment))
                            .findFirst().orElseThrow();
                    assertThat(f.getFileName().toString()).matches(
                            "^V20\\d{2}(0[1-9]|1[0-2])(0[1-9]|[12]\\d|3[01])_"
                                    + "([01]\\d|2[0-3])[0-5]\\d__[a-z0-9_]+\\.sql$");
                } catch (IOException e) {
                    throw new AssertionError(e);
                }
            }
        }
    }

    @Nested
    @DisplayName("AC1 渠道配置两列，与身份证渠道同构")
    class ChannelConfig {

        @Test
        void twoColumnsDefaultZeroWithNonNegativeCheck() {
            String flat = ddlOf("pawcoin_config_add_age_card_share").replaceAll("\\s+", " ");

            assertThat(flat).contains("add column age_card_share_reward bigint not null default 0");
            assertThat(flat).contains("add column age_card_share_daily_cap int not null default 0");
            assertThat(flat).contains("check (age_card_share_reward >= 0 and age_card_share_daily_cap >= 0)");
        }

        /** 与既有身份证渠道两列**同构**：同一件事在库里长同一个样子。 */
        @Test
        void mirrorsIdCardChannelColumns() {
            String age = ddlOf("pawcoin_config_add_age_card_share").replaceAll("\\s+", " ")
                    .replace("age_card", "CHANNEL");
            String idCard = ddlOf("pawcoin_config_add_id_card_share").replaceAll("\\s+", " ")
                    .replace("id_card", "CHANNEL");

            assertThat(age).contains("add column CHANNEL_share_reward bigint not null default 0");
            assertThat(idCard).contains("add column CHANNEL_share_reward bigint not null default 0");
        }

        /** AC5：**不设**独立月度上限 —— 与其它渠道共用全局那一个。 */
        @Test
        void noChannelLevelMonthlyCap() {
            String ddl = ddlOf("pawcoin_config_add_age_card_share");
            assertThat(ddl).doesNotContain("monthly");
        }
    }

    @Nested
    @DisplayName("AC4/AC5/AC6 判断顺序、共用月度上限、领奖不带内容")
    class ServiceShape {

        private String serviceSource() {
            try {
                return Files.readString(Path.of("src/main/java/com/tailtopia/share/service/"
                        + "AgeCardShareRewardService.java"), StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new AssertionError(e);
            }
        }

        /**
         * 🔴 顺序：去重命中 → 渠道日上限 → 月度全局额度。
         * 换了顺序会让最便宜的判断排在最贵的后面（去重是一次索引命中，月度额度要动写事务），
         * 而且会把额度占掉又退回去。
         */
        @Test
        void threeGatesInOrder() {
            String src = serviceSource();
            int dedup = src.indexOf("findByIdempotencyKey");
            int dailyCap = src.indexOf("countByUserIdAndShareDate");
            int monthly = src.indexOf("shareReward.tryReward");

            assertThat(dedup).isNotNegative();
            assertThat(dailyCap).as("日上限必须在去重之后").isGreaterThan(dedup);
            assertThat(monthly).as("月度额度必须在日上限之后").isGreaterThan(dailyCap);
        }

        /** AC5：共用全局月度上限（走 ShareRewardService），本渠道不另设一个。 */
        @Test
        void sharesTheGlobalMonthlyCap() {
            String src = serviceSource();
            assertThat(src).contains("shareReward.tryReward");
            assertThat(src).doesNotContain("MonthlyCap");
        }

        /**
         * 🔴 AC6：领奖调用只携带渠道标识与幂等键，**不带卡面内容、不上传图片**。
         * 卡面上有宠物名、由生日推算的年龄、头像 URL —— 任何一项进到请求体，
         * 都是把一次娱乐分享变成一次个人数据上报。
         */
        @Test
        void requestCarriesOnlyAnIdempotencyKey() {
            List<String> fields = Arrays.stream(
                    com.tailtopia.share.dto.AgeCardShareRewardRequest.class.getRecordComponents())
                    .map(java.lang.reflect.RecordComponent::getName).toList();

            assertThat(fields).containsExactly("idempotencyKey");
        }

        @Test
        void rewardEntrypointTakesNoCardContent() throws NoSuchMethodException {
            Method m = AgeCardShareRewardService.class
                    .getMethod("rewardAfterShare", long.class, String.class, Instant.class);
            assertThat(m.getParameterTypes())
                    .containsExactly(long.class, String.class, Instant.class);
        }

        /** 响应只回一个数，**不回原因** —— 回原因就会有人把它做成「你的额度用完了」。 */
        @Test
        void responseCarriesOnlyCoins() {
            assertThat(com.tailtopia.share.dto.AgeCardShareRewardResponse.class
                    .getRecordComponents()).hasSize(1);
        }

        /**
         * 幂等键**带上 userId** 再落库：客户端给的那串只在它自己那台设备上唯一，
         * 直接当全局唯一键用，等于让两个用户有概率互相顶掉对方的发放。
         */
        @Test
        void idempotencyKeyIsNamespacedByUser() {
            assertThat(serviceSource()).contains("CHANNEL_PREFIX + userId + \":\"");
        }
    }

    @Nested
    @DisplayName("注销级联（CLAUDE.md 安全攸关 D1）")
    class DeletionCascade {

        /**
         * 🔴 新增的带用户外键的表都要进注销级联。同一个用户的两种分享留痕在注销后
         * 表现必须一致 —— 一种消失、一种留着是最难发现的那类不一致。
         */
        @Test
        void ageCardLedgerIsDeletedWithTheAccount() throws IOException {
            String src = Files.readString(Path.of("src/main/java/com/tailtopia/share/service/"
                    + "ShareRewardDeletionService.java"), StandardCharsets.UTF_8);
            assertThat(src).contains("ageCardRewards.deleteByUserId(userId)");
        }

        /** 外键指向 users，与同胞表同构。 */
        @Test
        void hasUserForeignKey() {
            assertThat(ddlOf("age_card_share_rewards")).contains("references users (id)");
        }
    }
}
