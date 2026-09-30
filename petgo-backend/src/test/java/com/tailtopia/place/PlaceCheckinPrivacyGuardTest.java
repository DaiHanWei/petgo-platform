package com.tailtopia.place;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.tailtopia.place.domain.PlaceCheckin;
import com.tailtopia.place.domain.PlaceCheckinPet;
import com.tailtopia.place.domain.PlaceType;
import com.tailtopia.place.dto.PlaceCheckinResponse;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * V1.3.2 batch-a Story 1.1 · L0：打卡的坐标红线（AC2.10 / NFR-1）+ 响应契约（AC2.8）。
 *
 * <p>坐标只用于本次到场判定：<b>不落库、不进日志、不进响应</b>。这里把能静态钉住的全部钉住：
 * 两张表的迁移没有经纬度列、两个实体没有经纬度字段、打卡服务与控制器源码里没有带坐标的 log 调用、
 * 响应只有契约里的 7 个键（没有 distance / latitude / longitude）。
 */
class PlaceCheckinPrivacyGuardTest {

    private static final Path MAIN = Path.of("src/main/java/com/tailtopia/place");
    private static final Path MIGRATIONS = Path.of("src/main/resources/db/migration");

    private static String read(Path p) throws IOException {
        return Files.readString(p, StandardCharsets.UTF_8);
    }

    @Test
    void checkinTablesHaveNoCoordinateColumns() throws IOException {
        try (Stream<Path> files = Files.list(MIGRATIONS)) {
            List<Path> mine = files.filter(p -> p.getFileName().toString().endsWith("__add_place_checkin_pets.sql"))
                    .toList();
            assertThat(mine).hasSize(1);
            String ddl = read(mine.get(0)).toLowerCase();
            // 只看 DDL 行（注释里提到「坐标不落库」是允许的）。
            String code = Arrays.stream(ddl.split("\n")).filter(l -> !l.trim().startsWith("--"))
                    .reduce("", (a, b) -> a + "\n" + b);
            assertThat(code).doesNotContain("latitude").doesNotContain("longitude")
                    .doesNotContain(" lat ").doesNotContain(" lng ");
        }
    }

    @Test
    void checkinEntitiesHaveNoCoordinateFields() {
        for (Class<?> c : List.of(PlaceCheckin.class, PlaceCheckinPet.class,
                com.tailtopia.admin.places.domain.PlaceCheckin.class)) {
            assertThat(Arrays.stream(c.getDeclaredFields()).map(Field::getName).map(String::toLowerCase))
                    .as("%s 不得有坐标字段", c.getSimpleName())
                    .noneMatch(n -> n.contains("lat") || n.contains("lng") || n.contains("lon"));
        }
    }

    /** 打卡服务与控制器：没有 logger，或至少没有任何带坐标 / 请求体的 log 调用。 */
    @Test
    void checkinSourcesNeverLogCoordinates() throws IOException {
        for (Path p : List.of(MAIN.resolve("service/PlaceCheckinService.java"),
                MAIN.resolve("service/PlaceCheckinDeletionService.java"),
                MAIN.resolve("web/PlaceController.java"))) {
            String src = read(p);
            for (String line : src.split("\n")) {
                String l = line.trim();
                if (l.startsWith("//") || l.startsWith("*") || l.startsWith("/*")) {
                    continue;
                }
                if (l.matches(".*\\blog\\s*\\.\\s*(trace|debug|info|warn|error)\\s*\\(.*")) {
                    assertThat(l.toLowerCase()).as("%s 的日志行不得带坐标 / 请求体：%s", p.getFileName(), l)
                            .doesNotContain("lat").doesNotContain("lng").doesNotContain("lon")
                            .doesNotContain("req").doesNotContain("coord");
                }
            }
        }
        assertThat(read(MAIN.resolve("service/PlaceCheckinService.java")))
                .as("打卡服务不持有 logger（坐标经过它）")
                .doesNotContain("LoggerFactory");
    }

    @Test
    void responseHasExactlyTheContractKeys() {
        JsonMapper json = JsonMapper.builder()
                .changeDefaultPropertyInclusion(incl -> incl.withValueInclusion(JsonInclude.Include.NON_NULL))
                .build();
        @SuppressWarnings("unchecked")
        Map<String, Object> m = json.convertValue(new PlaceCheckinResponse("c".repeat(32), "p".repeat(32),
                "Kopi Kucing", PlaceType.CAFE, LocalDate.of(2026, 9, 30), true, 1L), Map.class);

        assertThat(m.keySet()).isEqualTo(Set.of("checkinToken", "placeToken", "placeName", "placeType",
                "visitDate", "isNewStamp", "visitCount"));
        assertThat(m.get("isNewStamp")).isEqualTo(true);
        assertThat(m.get("placeType")).isEqualTo("CAFE");
        assertThat(m.get("visitDate")).isEqualTo("2026-09-30");
    }
}
