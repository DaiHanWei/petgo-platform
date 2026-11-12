package com.tailtopia.analytics.identity;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * L0：关联表迁移文本守卫（analytics-pipeline Story 1.5 AC1 / AC6；读文件，不连库）。
 *
 * <p>测试库没有 {@code analytics_reader} 角色，授权改为断言迁移文本：含条件授权块、只授 SELECT。
 */
class IdentityLinksMigrationTextTest {

    private static final Path FILE =
            Path.of("src/main/resources/db/analytics-migration/V20261113_1040__create_identity_links.sql");

    /** 去掉 {@code --} 注释并压缩空白。 */
    private static String ddl() throws IOException {
        StringBuilder sb = new StringBuilder();
        for (String line : Files.readString(FILE, StandardCharsets.UTF_8).split("\n")) {
            int c = line.indexOf("--");
            sb.append(c >= 0 ? line.substring(0, c) : line).append(' ');
        }
        return sb.toString().replaceAll("\\s+", " ");
    }

    @Test
    void fileExists() {
        assertThat(Files.exists(FILE)).isTrue();
    }

    @Test
    void tableShape() throws IOException {
        String d = ddl();
        assertThat(d).contains("CREATE TABLE identity_links (");
        for (String col : new String[] {"anonymous_id varchar(64) NOT NULL", "user_id bigint NOT NULL",
                "user_key varchar(64) NOT NULL", "linked_at timestamptz NOT NULL", "message_id varchar(64) NOT NULL"}) {
            assertThat(d).contains(col);
        }
        assertThat(d).contains("CONSTRAINT pk_identity_links PRIMARY KEY (anonymous_id, linked_at)");
        assertThat(d).contains("CONSTRAINT uq_identity_links_message_id UNIQUE (message_id)");
        assertThat(d).contains("CREATE INDEX ix_identity_links_user_id ON identity_links (user_id);");
        // 不与业务表建外键
        assertThat(d).doesNotContainIgnoringCase("REFERENCES");
    }

    @Test
    void conditionalSelectOnlyGrant() throws IOException {
        String d = ddl();
        assertThat(d).contains("DO $$");
        assertThat(d).contains("IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = '${readerRole}') THEN");
        assertThat(d).contains("EXECUTE format('GRANT SELECT ON %I TO %I', 'identity_links', '${readerRole}');");
        assertThat(d).contains("RAISE NOTICE");
        String upper = d.toUpperCase();
        assertThat(upper).doesNotContainPattern("GRANT\\s+[^;']*\\b(INSERT|UPDATE|DELETE|TRUNCATE|ALL)\\b");
        assertThat(upper).containsOnlyOnce("GRANT ");
    }
}
