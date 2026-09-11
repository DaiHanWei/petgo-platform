package com.tailtopia.content.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tailtopia.content.domain.CommentLike;
import com.tailtopia.content.repository.CommentLikeRepository;
import com.tailtopia.shared.error.AppException;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * V1.3.0 批次 A · Story 2.4（L0）：评论点赞与热度排序（FR-114 · AD-A7 / AD-A8）。
 *
 * <p>本 story 的三条硬约束全是「**不要做什么**」，而「没做某件事」恰恰是最容易被后来人
 * 无意中做掉的 —— 加一个计数列、给 FeedCursor 多塞一元、给新表补一条删除逻辑，
 * 每一件单看都像"顺手优化"。所以这里用结构性断言把三条钉住，而不只是测功能。
 *
 * <p>排序与幂等的真实行为需要 DB（L1），见 story 的待验收清单。
 */
class CommentLikeAndHotOrderTest {

    private static final Path MIGRATION_DIR =
            Path.of("src", "main", "resources", "db", "migration");

    /**
     * 迁移文件的 **DDL 部分**（已剥掉 {@code --} 注释）。
     *
     * <p>注释里**本来就会**提到 `like_count` 这类禁止项 —— 那段文字正是在说明「为什么不加」。
     * 扫描时必须只看真正的语句，否则一条解释性注释就能把自己的断言弄红。
     */
    private static String migrationDdl() {
        return migrationSql().lines()
                .map(line -> {
                    int c = line.indexOf("--");
                    return c >= 0 ? line.substring(0, c) : line;
                })
                .collect(java.util.stream.Collectors.joining("\n"));
    }

    private static String migrationSql() {
        try (var files = Files.list(MIGRATION_DIR)) {
            List<Path> hits = files
                    .filter(p -> p.getFileName().toString().contains("comment_likes"))
                    .toList();
            assertThat(hits).as("应恰好有一支 comment_likes 迁移").hasSize(1);
            return Files.readString(hits.get(0), StandardCharsets.UTF_8).toLowerCase(Locale.ROOT);
        } catch (IOException e) {
            throw new AssertionError("读不到迁移目录 " + MIGRATION_DIR.toAbsolutePath(), e);
        }
    }

    @Nested
    @DisplayName("AC1 新表形态逐字对齐 content_likes")
    class TableShape {

        @Test
        void hasSameColumnsAndConstraintsAsContentLikes() {
            // 按「压平空白后的单行」比对，免得列对齐的空格数变一下断言就红。
            String flat = migrationDdl().replaceAll("\\s+", " ");

            assertThat(flat).contains("create table comment_likes");
            assertThat(flat).contains("id bigserial primary key");
            assertThat(flat).contains("comment_id bigint not null");
            assertThat(flat).contains("user_id bigint not null");
            assertThat(flat).contains("created_at timestamptz not null default now()");
            // 唯一约束防重复点赞 —— 幂等的真正保证在这一行，不在应用层的 exists 检查。
            assertThat(flat).contains("unique (comment_id, user_id)");
            // 两条外键，与 content_likes 同构。
            assertThat(flat).contains("references comments (id)");
            assertThat(flat).contains("references users (id)");
        }

        /** 一级、二级共用一张表：层级由 comments.parent_id 决定，与点赞无关。 */
        @Test
        void noPerLevelTables() {
            String ddl = migrationDdl();
            assertThat(ddl).doesNotContain("reply_likes");
            assertThat(ddl).doesNotContain("top_level_likes");
        }

        @Test
        void migrationFileNameIsTimestamped() {
            try (var files = Files.list(MIGRATION_DIR)) {
                Path f = files.filter(p -> p.getFileName().toString().contains("comment_likes"))
                        .findFirst().orElseThrow();
                assertThat(f.getFileName().toString()).matches(
                        "^V20\\d{2}(0[1-9]|1[0-2])(0[1-9]|[12]\\d|3[01])_"
                                + "([01]\\d|2[0-3])[0-5]\\d__[a-z0-9_]+\\.sql$");
            } catch (IOException e) {
                throw new AssertionError(e);
            }
        }
    }

    @Nested
    @DisplayName("AC2 🔴 不加冗余计数列")
    class NoCounterColumn {

        /**
         * 全库至今没有任何冗余计数列（`content_likes` 也是实时 COUNT）。
         * 开这个头就要处理并发增减、回填与对账 —— 而本表的规模前提根本用不上它。
         */
        @Test
        void migrationDeclaresNoCountColumn() {
            String ddl = migrationDdl();
            assertThat(ddl).doesNotContain("like_count");
            assertThat(ddl).doesNotContain("likes_count");
            assertThat(ddl).doesNotContain("counter");
        }

        /** 实体上同样不许有 —— 加了字段而不加列，validate 会在启动时炸，但那太晚了。 */
        @Test
        void entityHasNoCountField() {
            List<String> fields = Arrays.stream(CommentLike.class.getDeclaredFields())
                    .map(java.lang.reflect.Field::getName)
                    .toList();
            assertThat(fields).containsExactlyInAnyOrder("id", "commentId", "userId", "createdAt");
        }
    }

    @Nested
    @DisplayName("AC5 🔴 独立游标，FeedCursor 一字未改")
    class CursorIsolation {

        @Test
        void hotCursorRoundTripsAllThreeElements() {
            Instant ts = Instant.parse("2026-09-11T04:19:00.123456Z");
            CommentHotCursor c = new CommentHotCursor(42, ts, 7L);

            CommentHotCursor back = CommentHotCursor.decode(c.encode());

            assertThat(back.likeCount()).isEqualTo(42);
            assertThat(back.createdAt()).isEqualTo(ts);
            assertThat(back.id()).isEqualTo(7L);
        }

        /**
         * 🔴 <b>两种 token 互不通用，且传错时当场失败</b>。
         *
         * <p>这正是不去改 {@code FeedCursor} 的理由：若给它加一元，老客户端手里的二元组 token
         * 进到三元组解码里，可能"解出一个看似合理的错位游标"—— 项目自家 {@code FeedRankCursor}
         * 的注释里已经写过这个教训。分成两个类型后，错用是 422，不是静默错位。
         */
        @Test
        void feedCursorTokenIsRejectedByHotCursor() {
            String feedToken = new FeedCursor(Instant.parse("2026-09-11T04:19:00Z"), 7L).encode();

            assertThatThrownBy(() -> CommentHotCursor.decode(feedToken))
                    .isInstanceOf(AppException.class);
        }

        @Test
        void hotCursorTokenIsRejectedByFeedCursor() {
            String hotToken =
                    new CommentHotCursor(1, Instant.parse("2026-09-11T04:19:00Z"), 7L).encode();

            assertThatThrownBy(() -> FeedCursor.decode(hotToken))
                    .isInstanceOf(AppException.class);
        }

        /**
         * {@code FeedCursor} 仍是**二元组** —— 它被 Feed 与二级回复共用，多一个字段就是溢出。
         * 这条断言是「不改共享类」这条纪律的执行者。
         */
        @Test
        void feedCursorStillHasExactlyTwoComponents() {
            assertThat(FeedCursor.class.getRecordComponents()).hasSize(2);
            assertThat(Arrays.stream(FeedCursor.class.getRecordComponents())
                    .map(java.lang.reflect.RecordComponent::getName))
                    .containsExactly("createdAt", "id");
        }

        @Test
        void malformedTokenIs422NotCrash() {
            assertThatThrownBy(() -> CommentHotCursor.decode("not-base64!!"))
                    .isInstanceOf(AppException.class);
            assertThatThrownBy(() -> CommentHotCursor.decode(
                    java.util.Base64.getUrlEncoder().withoutPadding()
                            .encodeToString("h1:only:three".getBytes(StandardCharsets.UTF_8))))
                    .isInstanceOf(AppException.class);
        }
    }

    @Nested
    @DisplayName("AC6/AC7 索引两侧 + 批量取数")
    class IndexAndBatching {

        /** 索引只支撑聚合与过滤两侧；排序本身没有索引可用（这是 AC2 的直接后果，不是遗漏）。 */
        @Test
        void indexesCoverAggregationAndFilterSides() {
            String sql = migrationSql();
            assertThat(sql).contains("on comment_likes (comment_id)");
            assertThat(sql).contains("on comments (post_id, parent_id, deleted_at, created_at)");
        }

        /**
         * 🔴 AC7：仓库**刻意不提供任何逐条取数方法** —— 拿不到逐条版本，就写不出 N+1。
         *
         * <p>一页 20 条评论逐条查赞数是 20 次查询，逐条查「我赞没赞」又是 20 次。
         * 允许存在的单条方法只有幂等/取消那两个（作用于一个评论+一个用户，本就不是批量场景）。
         */
        @Test
        void repositoryExposesNoPerCommentCountLookup() {
            List<String> names = Arrays.stream(CommentLikeRepository.class.getDeclaredMethods())
                    .map(Method::getName)
                    .toList();

            assertThat(names).containsExactlyInAnyOrder(
                    "existsByCommentIdAndUserId", // 幂等快速短路（单条+单用户）
                    "deleteByCommentIdAndUserId", // 取消点赞（单条+单用户）
                    "countByCommentIdIn", // 批量赞数
                    "findLikedCommentIds"); // 批量已赞集合
            assertThat(names).noneMatch(n -> n.equals("countByCommentId"));
        }
    }

    @Nested
    @DisplayName("AC8 注销级联与 content_likes 同一口径")
    class DeletionParity {

        /**
         * 注销走「就地匿名化 user 行、**不物理删**」（决策 D1/A）：
         * `content_likes` 因此没有任何按用户删除的路径，`comment_likes` 必须一样。
         *
         * <p>给新表单独加一个 `deleteByUserId` 就是另立了一套口径 ——
         * 那会让同一个用户的两种点赞在注销后表现不同（一种消失、一种留着）。
         */
        @Test
        void neitherLikeTableHasDeleteByUser() {
            List<String> commentLikeMethods =
                    Arrays.stream(CommentLikeRepository.class.getDeclaredMethods())
                            .map(Method::getName).toList();
            List<String> contentLikeMethods = Arrays.stream(
                    com.tailtopia.content.repository.ContentLikeRepository.class
                            .getDeclaredMethods())
                    .map(Method::getName).toList();

            assertThat(commentLikeMethods).noneMatch(n -> n.toLowerCase(Locale.ROOT)
                    .contains("deletebyuser"));
            assertThat(contentLikeMethods)
                    .as("参照物：content_likes 本来就没有按用户删除的路径")
                    .noneMatch(n -> n.toLowerCase(Locale.ROOT).contains("deletebyuser"));
        }

        /** 外键与 content_likes 同为指向 users(id) 的 RESTRICT（不写 ON DELETE CASCADE）。 */
        @Test
        void userForeignKeyIsRestrictLikeContentLikes() {
            String ddl = migrationDdl();
            assertThat(ddl).contains("references users (id)");
            assertThat(ddl)
                    .as("硬删 user 行会被外键挡住 —— 这正是注销改走匿名化的原因，别加级联删")
                    .doesNotContain("on delete cascade");
        }
    }
}
