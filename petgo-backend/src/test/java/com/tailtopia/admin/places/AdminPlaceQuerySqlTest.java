package com.tailtopia.admin.places;

import static org.assertj.core.api.Assertions.assertThat;

import com.tailtopia.admin.places.dto.PlaceFilter;
import com.tailtopia.admin.places.service.AdminPlaceQueryService;
import org.junit.jupiter.api.Test;

/** L0：场所列表 / 摘要 SQL 的静态形状守卫（V1.3.0 Story 5.2 复审 #1：文本块剥尾随空格曾把 `WHERE ` 拼成 `WHEREp.`）与筛选解析。 */
class AdminPlaceQuerySqlTest {

    @Test
    void sqlKeywordsAreSeparatedFromWhereClause() {
        for (String sql : new String[] {AdminPlaceQueryService.LIST_SQL, AdminPlaceQueryService.COUNT_SQL, AdminPlaceQueryService.SUMMARY_SQL}) {
            assertThat(sql).containsPattern("WHERE\\s+p\\.deleted_at IS NULL").doesNotContain("WHEREp").contains(":q").contains(":city");
        }
        assertThat(AdminPlaceQueryService.SUMMARY_SQL).contains("FILTER (WHERE p.status = 'ACTIVE')").contains("AT TIME ZONE 'Asia/Jakarta'")
                .contains("r.status = 'PENDING'").contains("SUM(p.checkin_count)");
        assertThat(AdminPlaceQueryService.LIST_SQL).contains("ORDER BY p.created_at DESC, p.id DESC LIMIT :limit OFFSET :offset");
        assertThat(AdminPlaceQueryService.escapeLike("50%_x\\")).isEqualTo("50\\%\\_x\\\\");
    }

    @Test
    void filterNormalizesBlanksAndBadStatus() {
        PlaceFilter f = PlaceFilter.of(" kopi ", "", "bogus", null, -3);
        assertThat(f.q()).isEqualTo("kopi");
        assertThat(f.type()).isNull();
        assertThat(f.status()).isNull();
        assertThat(f.statusParam()).isEmpty();
        assertThat(f.page()).isZero();
        assertThat(PlaceFilter.of(null, "CAFE", "delisted", "Jakarta", 2).status()).isEqualTo(com.tailtopia.admin.places.domain.PlaceStatus.DELISTED);
    }
}
