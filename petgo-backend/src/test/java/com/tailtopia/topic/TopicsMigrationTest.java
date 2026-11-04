package com.tailtopia.topic;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** L0：V1.4.2 social-topic · Story 4.1 · AC1 / AC2 迁移文本守卫（读文件，不连库）。 */
class TopicsMigrationTest {

    private static final Path TOPICS =
            Path.of("src/main/resources/db/migration/V20261104_2050__create_topics_and_content_topics.sql");
    private static final Path VIEW_DAYS =
            Path.of("src/main/resources/db/migration/V20261104_2060__create_content_post_view_days.sql");

    /** 去掉 {@code --} 注释并压缩空白。 */
    private static String ddl(Path file) throws IOException {
        StringBuilder sb = new StringBuilder();
        for (String line : Files.readString(file, StandardCharsets.UTF_8).split("\n")) {
            int c = line.indexOf("--");
            sb.append(c >= 0 ? line.substring(0, c) : line).append(' ');
        }
        return sb.toString().replaceAll("\\s+", " ");
    }

    @Test
    void topicsTableShape() throws IOException {
        String d = ddl(TOPICS);
        assertThat(d).contains("CREATE TABLE topics (")
                .contains("name_display VARCHAR(30) NOT NULL")
                .contains("name_norm VARCHAR(30) NOT NULL")
                .contains("status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE'")
                .contains("post_count INTEGER NOT NULL DEFAULT 0")
                .contains("heat_score NUMERIC(12,1) NOT NULL DEFAULT 0")
                .contains("featured_order INTEGER,")
                .contains("CONSTRAINT uq_topics_name_norm UNIQUE (name_norm)")
                .contains("CONSTRAINT ck_topics_type CHECK (type IN ('OFFICIAL', 'USER'))")
                .contains("CONSTRAINT ck_topics_status CHECK (status IN ('ACTIVE', 'HIDDEN', 'DISABLED'))")
                .contains("CHECK (featured_order IS NULL OR featured_order BETWEEN 1 AND 5)")
                .contains("CHECK (featured_order IS NULL OR status = 'ACTIVE')");
    }

    @Test
    void topicsIndexes() throws IOException {
        String d = ddl(TOPICS);
        assertThat(d).contains("CREATE UNIQUE INDEX uq_topics_featured_order ON topics (featured_order) "
                        + "WHERE featured_order IS NOT NULL")
                .contains("CREATE INDEX idx_topics_name_norm_prefix ON topics (name_norm text_pattern_ops)")
                .contains("CREATE INDEX idx_topics_status_heat ON topics (status, heat_score DESC)");
    }

    @Test
    void contentTopicsCompositeKeyAndCascade() throws IOException {
        String d = ddl(TOPICS);
        assertThat(d).contains("CREATE TABLE content_topics (")
                .contains("CONSTRAINT pk_content_topics PRIMARY KEY (post_id, topic_id)")
                .contains("FOREIGN KEY (post_id) REFERENCES content_posts (id) ON DELETE CASCADE")
                .contains("CONSTRAINT fk_content_topics_topic FOREIGN KEY (topic_id) REFERENCES topics (id) )")
                .contains("CREATE INDEX idx_content_topics_topic_post ON content_topics (topic_id, post_id)");
    }

    @Test
    void noSmallintAnywhere() throws IOException {
        // Hibernate int 映射 int4，SMALLINT 会让 ddl-auto=validate 全红（含注释在内全文禁）。
        assertThat(Files.readString(TOPICS, StandardCharsets.UTF_8).toUpperCase()).doesNotContain("SMALLINT");
        assertThat(Files.readString(VIEW_DAYS, StandardCharsets.UTF_8).toUpperCase()).doesNotContain("SMALLINT");
    }

    @Test
    void viewDaysTableShape() throws IOException {
        String d = ddl(VIEW_DAYS);
        assertThat(d).contains("CREATE TABLE content_post_view_days (")
                .contains("view_date DATE NOT NULL")
                .contains("viewer_key VARCHAR(40) NOT NULL")
                .contains("CONSTRAINT pk_content_post_view_days PRIMARY KEY (post_id, view_date, viewer_key)")
                .contains("FOREIGN KEY (post_id) REFERENCES content_posts (id) ON DELETE CASCADE")
                .contains("CREATE INDEX idx_content_post_view_days_date ON content_post_view_days (view_date)");
    }
}
