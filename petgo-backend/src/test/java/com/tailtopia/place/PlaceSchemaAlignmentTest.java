package com.tailtopia.place;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

import com.tailtopia.place.domain.Place;
import com.tailtopia.place.domain.PlaceStatus;
import com.tailtopia.place.domain.PlaceTag;
import com.tailtopia.place.domain.PlaceType;
import com.tailtopia.place.repository.PlaceRepository;
import com.tailtopia.place.service.DefaultPlaceCityResolver;
import com.tailtopia.place.service.PlacePhotoService;
import com.tailtopia.shared.error.AppException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * 2026-09-18 场所表对齐（规格 spec-v130-places-schema-alignment.md）的 L0 守卫。
 *
 * <p>后台与 App 两套实体映射同一批表：任何一边的枚举、列名、取值漂了，
 * 都是「能编译、启动或读数据时才炸」—— 这里把能静态钉住的全部钉住。
 */
class PlaceSchemaAlignmentTest {

    private static final Path MIGRATIONS = Path.of("src/main/resources/db/migration");

    private static String migration(String fragment) throws IOException {
        try (Stream<Path> files = Files.list(MIGRATIONS)) {
            Path f = files.filter(p -> p.getFileName().toString().contains(fragment)).findFirst().orElseThrow();
            return Files.readString(f, StandardCharsets.UTF_8);
        }
    }

    @Nested
    class OneSchemaOnly {

        /** 只能有一个迁移建 places —— 两套建表正是这次事故本身。 */
        @Test
        void exactlyOneMigrationCreatesThePlacesTables() throws IOException {
            try (Stream<Path> files = Files.list(MIGRATIONS)) {
                List<String> creators = files.filter(p -> {
                    try {
                        return Files.readString(p, StandardCharsets.UTF_8).matches("(?s).*CREATE TABLE (IF NOT EXISTS )?places\\b.*");
                    } catch (IOException e) {
                        throw new AssertionError(e);
                    }
                }).map(p -> p.getFileName().toString()).toList();
                assertThat(creators).containsExactly("V20260909_1749__init_places.sql");
            }
        }

        /** 类型 CHECK 的值域 = App 枚举 = 后台枚举（决策 D1）。 */
        @Test
        void placeTypeValuesAgreeEverywhere() throws IOException {
            List<String> app = Arrays.stream(PlaceType.values()).map(Enum::name).toList();
            List<String> admin = Arrays.stream(com.tailtopia.admin.places.domain.PlaceType.values()).map(Enum::name).toList();
            assertThat(admin).containsExactlyElementsOf(app);
            String ddl = migration("align_places_for_app");
            for (String v : app) {
                assertThat(ddl).contains("'" + v + "'");
            }
        }

        /** App 读场所状态必须认全后台三值，否则读到 MERGED / DELISTED 的行枚举解析直接抛。 */
        @Test
        void placeStatusValuesAgree() {
            assertThat(Arrays.stream(PlaceStatus.values()).map(Enum::name))
                    .containsExactlyElementsOf(Arrays.stream(com.tailtopia.admin.places.domain.PlaceStatus.values())
                            .map(Enum::name).toList());
        }

        /** 后台录入 / 编辑的标签值域直接取 App 的 PlaceTag（App 按枚举读，写进未知值整页 500）。 */
        @Test
        void adminTagWhitelistIsTheAppEnum() {
            assertThat(com.tailtopia.admin.places.dto.PlaceEditForm.KNOWN_TAGS)
                    .containsExactlyElementsOf(Arrays.stream(PlaceTag.values()).map(Enum::name).toList());
        }

        /** 计数缓存列已删（D3）—— 实体上不许再长回来。 */
        @Test
        void noCountCacheColumnsOnEitherEntity() throws IOException {
            assertThat(migration("align_places_for_app")).contains("DROP COLUMN photo_count")
                    .contains("DROP COLUMN not_recommend_count");
            for (Class<?> c : List.of(Place.class, com.tailtopia.admin.places.domain.Place.class)) {
                assertThat(Arrays.stream(c.getDeclaredFields()).map(f -> f.getName().toLowerCase()))
                        .noneMatch(n -> n.endsWith("count"));
            }
        }

        /** 两套实体同表不同 JPA 实体名，否则 Hibernate 启动即报实体名重复。 */
        @Test
        void adminEntitiesHaveDistinctJpaNames() {
            for (Class<?> c : List.of(com.tailtopia.admin.places.domain.Place.class,
                    com.tailtopia.admin.places.domain.PlaceComment.class,
                    com.tailtopia.admin.places.domain.PlacePhoto.class,
                    com.tailtopia.admin.places.domain.PlaceReport.class)) {
                assertThat(c.getAnnotation(jakarta.persistence.Entity.class).name()).isEqualTo("Admin" + c.getSimpleName());
            }
        }
    }

    @Nested
    class MergedRedirect {

        private final PlaceRepository repo = mock(PlaceRepository.class,
                withSettings().defaultAnswer(Answers.CALLS_REAL_METHODS));

        private Place place(long id, String token, PlaceStatus status, Long mergedInto) {
            Place p = Place.mark(token, "X", PlaceType.CAFE, List.of(PlaceTag.PET_MENU), -6.2, 106.8, "Jl", null, 1L, "Jakarta");
            ReflectionTestUtils.setField(p, "id", id);
            ReflectionTestUtils.setField(p, "status", status);
            ReflectionTestUtils.setField(p, "mergedIntoId", mergedInto);
            return p;
        }

        @Test
        void activeResolvesToItself() {
            Place a = place(1L, "a", PlaceStatus.ACTIVE, null);
            when(repo.findUndeletedByPublicToken("a")).thenReturn(Optional.of(a));
            assertThat(repo.resolveForView("a")).containsSame(a);
        }

        @Test
        void mergedResolvesToTheKeptPlace() {
            Place keep = place(2L, "keep", PlaceStatus.ACTIVE, null);
            when(repo.findUndeletedByPublicToken("old")).thenReturn(Optional.of(place(1L, "old", PlaceStatus.MERGED, 2L)));
            when(repo.findVisibleById(2L)).thenReturn(Optional.of(keep));
            assertThat(repo.resolveForView("old")).containsSame(keep);
        }

        /** 只跳一层；目标不可见 / 下架 / 不存在，一律与「从来没有过」同一结果（AC7）。 */
        @Test
        void delistedOrDanglingMergeIsNotFound() {
            when(repo.findUndeletedByPublicToken("d")).thenReturn(Optional.of(place(1L, "d", PlaceStatus.DELISTED, null)));
            when(repo.findUndeletedByPublicToken("m")).thenReturn(Optional.of(place(3L, "m", PlaceStatus.MERGED, 9L)));
            when(repo.findVisibleById(9L)).thenReturn(Optional.empty());
            when(repo.findUndeletedByPublicToken("none")).thenReturn(Optional.empty());
            assertThat(repo.resolveForView("d")).isEmpty();
            assertThat(repo.resolveForView("m")).isEmpty();
            assertThat(repo.resolveForView("none")).isEmpty();
        }
    }

    @Nested
    class PhotoObjectKeys {

        private final PlacePhotoService service = new PlacePhotoService(null, null, null, PlaceTestSupport.oss());

        /** URL → key → URL 原样往返：App 看到的照片地址与改动前逐字一致（决策 D5）。 */
        @Test
        void bucketUrlRoundTrips() {
            List<String> keys = service.toObjectKeys(List.of("https://cdn/places/7/a.jpg"));
            assertThat(keys).containsExactly("places/7/a.jpg");
            assertThat(PlaceTestSupport.oss().publicUrl(keys.get(0))).isEqualTo("https://cdn/places/7/a.jpg");
        }

        /** 🔒 不是本平台公开桶的地址整批拒（此前原样落库，任意外链会被当成平台的图分发）。 */
        @Test
        void foreignOrTraversalUrlsAreRejected() {
            for (String bad : List.of("https://evil.example/a.jpg", "https://cdn.evil/a.jpg", "https://cdn/",
                    "https://cdn/../secret.jpg", "")) {
                assertThatThrownBy(() -> service.toObjectKeys(List.of("https://cdn/ok.jpg", bad)))
                        .as(bad).isInstanceOf(AppException.class);
            }
        }

        @Test
        void queryStringIsStrippedFromTheKey() {
            assertThat(service.toObjectKeys(List.of("https://cdn/a.jpg?x-oss-process=image/resize,w_320")))
                    .containsExactly("a.jpg");
        }
    }

    @Nested
    class DefaultCity {

        /** 城市只来自配置项（D2 为多城市预留）：换配置值结果就跟着变，证明没有写死。 */
        @Test
        void comesFromConfigurationOnly() {
            assertThat(new DefaultPlaceCityResolver("Jakarta").resolve(null, null)).isEqualTo("Jakarta");
            assertThat(new DefaultPlaceCityResolver(" Surabaya ").resolve(null, null)).isEqualTo("Surabaya");
            assertThat(new DefaultPlaceCityResolver("Bandung").defaultCity()).isEqualTo("Bandung");
        }

        @Test
        void blankOrTooLongConfigFailsFast() {
            assertThatThrownBy(() -> new DefaultPlaceCityResolver(" ")).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> new DefaultPlaceCityResolver("x".repeat(61))).isInstanceOf(IllegalStateException.class);
        }

        /** 默认城市只能出现在 application.yml 这一处（后台表单也读它，不在模板里写死）。 */
        @Test
        void jakartaIsNotHardcodedInPlaceCode() throws IOException {
            try (Stream<Path> files = Stream.concat(
                    Files.walk(Path.of("src/main/java/com/tailtopia/place")),
                    Stream.concat(Files.walk(Path.of("src/main/java/com/tailtopia/admin/places")),
                            Files.walk(Path.of("src/main/resources/templates/admin/fragments"))))) {
                List<String> offenders = files.filter(Files::isRegularFile)
                        .filter(p -> p.getFileName().toString().matches(".*(places?|Place).*\\.(java|html)"))
                        .filter(p -> {
                            try {
                                return Files.readAllLines(p, StandardCharsets.UTF_8).stream()
                                        .map(String::strip)
                                        .filter(l -> !l.startsWith("*") && !l.startsWith("//") && !l.startsWith("/*"))
                                        .anyMatch(l -> l.contains("\"Jakarta\"") || l.contains("value=\"Jakarta\""));
                            } catch (IOException e) {
                                throw new AssertionError(e);
                            }
                        }).map(Path::toString).toList();
                assertThat(offenders).isEmpty();
            }
        }
    }
}
