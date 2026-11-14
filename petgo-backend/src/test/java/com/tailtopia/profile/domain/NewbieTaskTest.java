package com.tailtopia.profile.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** L0：V1.5.0 onboarding-recall Story 2.1 · AC2 —— 7 项固定顺序与对外序号。 */
class NewbieTaskTest {

    @Test
    void 七项固定顺序() {
        assertThat(NewbieTask.ALL).containsExactly(NewbieTask.FIRST_DIARY, NewbieTask.FIRST_LIKE_RECEIVED,
                NewbieTask.FIRST_FOLLOW, NewbieTask.FIRST_LIKE_GIVEN, NewbieTask.FIRST_COMMENT,
                NewbieTask.FIRST_REPLY, NewbieTask.ONE_WEEK);
    }

    @Test
    void 对外序号1到7() {
        assertThat(NewbieTask.FIRST_DIARY.ordinalForAnalytics()).isEqualTo(1);
        assertThat(NewbieTask.ONE_WEEK.ordinalForAnalytics()).isEqualTo(7);
    }

    @Test
    void 键名与迁移约束逐字一致() throws Exception {
        String sql = java.nio.file.Files.readString(java.nio.file.Path.of(
                "src/main/resources/db/migration/V20261115_0300__create_user_newbie_marks.sql"));
        for (NewbieTask t : NewbieTask.values()) {
            assertThat(sql).contains("'" + t.name() + "'");
        }
        assertThat(sql).contains("CREATE TABLE IF NOT EXISTS user_newbie_marks")
                .contains("PRIMARY KEY (user_id, task_key)")
                .contains("ON DELETE CASCADE")
                .contains("ck_user_newbie_marks_source CHECK (source IN ('RUNTIME', 'BACKFILL'))")
                .contains("DROP + ADD");
    }
}
