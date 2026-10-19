package com.tailtopia.profile;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * V1.4.0 multi-pet Story 1.2 · AC4（AD-1、V-2、§5「禁止按账号取唯一宠物」）· L0 源码守卫。
 *
 * <p>纯文件扫描（Maven 工作目录 = {@code petgo-backend/}），只扫 {@code src/main/java}。匹配前先去掉
 * {@code //} 行注释与块注释（Javadoc 里提到旧方法名不算违规），字符串字面量保留。
 *
 * <ol>
 *   <li>不得出现 {@code findByOwnerId(}（仓储方法已删；同名包装方法也不得存在）；</li>
 *   <li>不得出现 {@code findByOwnerIdIn(}（无序批量）；</li>
 *   <li>不得声明返回单个 {@code Optional} 的按 owner 宠物查询（白名单：名字写明「默认」的
 *       {@code findDefault} / {@code findDefaultIdentityByOwner}）；</li>
 *   <li>不得残留 Story 1.1 留下的 1.2 待办标记（扫原文，含注释）；</li>
 *   <li>字符串字面量里同时含 {@code pet_profiles} 与 {@code owner_id} 的文件集合 = 已审核白名单，且白名单文件不含
 *       {@code LIMIT 1}。</li>
 * </ol>
 */
class PetOwnerLookupGuardTest {

    private static final Path MAIN = Path.of("src/main/java/com/tailtopia");

    /** 含 findOne / findFirst / findTop / getBy 等 Spring Data 单行变体（1.2 复审补强）。 */
    static final Pattern FIND_BY_OWNER_ID = Pattern.compile(
            "\\b(?:find|get|read|query)(?:One|First|Top\\d*)?ByOwnerId\\s*\\(");
    static final Pattern FIND_BY_OWNER_ID_IN = Pattern.compile("\\bfindByOwnerIdIn\\s*\\(");
    static final Pattern OPTIONAL_BY_OWNER = Pattern.compile(
            "Optional<\\s*(PetProfile|PetIdentityView|PetPassportSubject|OwnedPetRef|Long)\\s*>\\s+"
                    + "(?!findDefault\\b|findDefaultIdentityByOwner\\b)\\w*By(Owner|OwnerId)(?![a-z])\\w*\\s*\\(");
    /** 标记拆开拼，避免本文件被同类扫描误伤。 */
    static final String TODO_MARKER = "TODO(" + "v1.4.0-multi-pet-1.2)";
    private static final Pattern LIMIT_ONE = Pattern.compile("\\bLIMIT\\s+1\\b", Pattern.CASE_INSENSITIVE);

    /** 已逐条审核、多宠安全的原生 SQL 文件（story 1.2 Dev Notes 表 S）+ 实体列映射。相对 {@link #MAIN}。 */
    private static final Set<String> NATIVE_SQL_WHITELIST = Set.of(
            "admin/dashboard/metrics/CumulativePetOwnersMetricQuery.java",
            "admin/dashboard/metrics/DiaryPetOwnersMetricQuery.java",
            "admin/dashboard/metrics/NewPetOwnersMetricQuery.java",
            "admin/moderation/service/UnifiedTicketQueryService.java",
            "admin/usermgmt/service/AdminUserPurchasesQuery.java",
            "auth/service/OpsAudienceSql.java",
            "passport/service/BoardingPassDuplicateQuery.java",
            "profile/domain/PetProfile.java");

    private static List<Path> sources() throws IOException {
        try (Stream<Path> s = Files.walk(MAIN)) {
            return s.filter(p -> p.toString().endsWith(".java")).sorted().toList();
        }
    }

    private static String rel(Path p) {
        return MAIN.relativize(p).toString().replace('\\', '/');
    }

    @Test
    void noFindByOwnerIdCalls() throws IOException {
        assertNoMatch(FIND_BY_OWNER_ID,
                "按账号取唯一宠物的 findByOwnerId 已删除：带 petId 用 PetResolver.resolve / findByIdAndOwnerId，"
                        + "取默认宠物用 findDefault，列表用 findAllByOwnerIdOrderByCreatedAtAscIdAsc");
    }

    @Test
    void noUnorderedFindByOwnerIdIn() throws IOException {
        assertNoMatch(FIND_BY_OWNER_ID_IN,
                "无序批量 findByOwnerIdIn 已删除：一律用 findAllByOwnerIdInOrderByCreatedAtAscIdAsc + "
                        + "DefaultPetPolicy.pickPerOwner");
    }

    @Test
    void noOptionalSinglePetByOwnerDeclarations() throws IOException {
        assertNoMatch(OPTIONAL_BY_OWNER,
                "不得声明返回单个 Optional 的按 owner 宠物查询（多宠下语义不明）：改为按 petId 取、返回列表，"
                        + "或取默认宠物并命名为 findDefault*");
    }

    @Test
    void noLeftoverStory11TodoMarkers() throws IOException {
        List<String> hits = new ArrayList<>();
        for (Path p : sources()) {
            String raw = Files.readString(p, StandardCharsets.UTF_8);
            int idx = raw.indexOf(TODO_MARKER);
            while (idx >= 0) {
                hits.add(rel(p) + ":" + lineOf(raw, idx));
                idx = raw.indexOf(TODO_MARKER, idx + 1);
            }
        }
        assertThat(hits).as("Story 1.1 留下的 1.2 待办标记必须清零：控制器把解析出的宠物 id 传入服务层").isEmpty();
    }

    @Test
    void nativeSqlOnPetProfilesByOwnerIsWhitelisted() throws IOException {
        Set<String> found = new TreeSet<>();
        List<String> limitOne = new ArrayList<>();
        for (Path p : sources()) {
            String literals = String.join("\n", stringLiterals(Files.readString(p, StandardCharsets.UTF_8)));
            String lower = literals.toLowerCase(Locale.ROOT);
            if (lower.contains("pet_profiles") && lower.contains("owner_id")) {
                found.add(rel(p));
                if (LIMIT_ONE.matcher(literals).find()) {
                    limitOne.add(rel(p));
                }
            }
        }
        assertThat(found)
                .as("新增了按 owner 查宠物的原生 SQL，请确认不是取单行后加入白名单并写明理由")
                .isEqualTo(new TreeSet<>(NATIVE_SQL_WHITELIST));
        assertThat(limitOne).as("白名单原生 SQL 不得按 owner 取单行（LIMIT 1）").isEmpty();
    }

    /** 反例自检：匹配函数必须能命中典型违规，防止正则写坏后恒绿。 */
    @Test
    void matchersCatchKnownViolations() {
        assertThat(violations(FIND_BY_OWNER_ID, stripComments("var p = profiles.findByOwnerId(1L);"))).hasSize(1);
        assertThat(violations(FIND_BY_OWNER_ID, stripComments("// profiles.findByOwnerId(1L)"))).isEmpty();
        assertThat(violations(FIND_BY_OWNER_ID, stripComments("/** {@code findByOwnerId(x)} */ int a;"))).isEmpty();
        assertThat(violations(FIND_BY_OWNER_ID, stripComments("profiles.findByOwnerIdIn(ids);"))).isEmpty();
        assertThat(violations(FIND_BY_OWNER_ID_IN, stripComments("pets.findByOwnerIdIn (ids);"))).hasSize(1);
        // 1.2 复审补强：单行变体与「ByOwnerId 后还有条件 / 排序」的 Optional 声明也要命中。
        assertThat(violations(FIND_BY_OWNER_ID, stripComments("pets.findFirstByOwnerId(1L);"))).hasSize(1);
        assertThat(violations(FIND_BY_OWNER_ID, stripComments("pets.getByOwnerId(1L);"))).hasSize(1);
        assertThat(violations(FIND_BY_OWNER_ID, stripComments("pets.existsByOwnerId(1L);"))).isEmpty();
        assertThat(violations(OPTIONAL_BY_OWNER, stripComments(
                "Optional<PetProfile> findFirstByOwnerIdOrderByCreatedAtAsc(long o);"))).hasSize(1);
        assertThat(violations(OPTIONAL_BY_OWNER, stripComments(
                "Optional<PetProfile> findByOwnerIdAndPetType(long o, PetType t);"))).hasSize(1);
        assertThat(violations(OPTIONAL_BY_OWNER, stripComments(
                "Optional<PetIdentityView> findDefaultIdentityByOwner(Long o);"))).isEmpty();
        assertThat(violations(OPTIONAL_BY_OWNER, stripComments(
                "Optional<PetProfile> findByOwnership(long o);"))).isEmpty();
        assertThat(violations(OPTIONAL_BY_OWNER,
                stripComments("public Optional<PetIdentityView> findIdentityByOwner(Long ownerId) {"))).hasSize(1);
        assertThat(violations(OPTIONAL_BY_OWNER,
                stripComments("Optional<Long> findPetIdByOwner(long ownerId);"))).hasSize(1);
        assertThat(violations(OPTIONAL_BY_OWNER,
                stripComments("default Optional<PetProfile> findDefault(long ownerId) {"))).isEmpty();
        assertThat(violations(OPTIONAL_BY_OWNER,
                stripComments("Optional<PetIdentityView> findDefaultIdentityByOwner(Long ownerId) {"))).isEmpty();
        assertThat(violations(OPTIONAL_BY_OWNER,
                stripComments("Optional<PetProfile> findByIdAndOwnerId(long id, long ownerId);"))).isEmpty();
        assertThat(stringLiterals("String q = \"SELECT 1 FROM pet_profiles p WHERE p.owner_id = :u\"; // owner_id"))
                .containsExactly("SELECT 1 FROM pet_profiles p WHERE p.owner_id = :u");
        assertThat(stringLiterals("String q = \"\"\"\n  select * from pet_profiles\n  \"\"\"; /* \"owner_id\" */"))
                .containsExactly("\n  select * from pet_profiles\n  ");
    }

    // ---- 扫描实现 ----

    private void assertNoMatch(Pattern pattern, String howToFix) throws IOException {
        List<String> hits = new ArrayList<>();
        for (Path p : sources()) {
            String code = stripComments(Files.readString(p, StandardCharsets.UTF_8));
            for (int line : violations(pattern, code)) {
                hits.add(rel(p) + ":" + line);
            }
        }
        assertThat(hits).as(howToFix).isEmpty();
    }

    /** 命中行号（1 起）。 */
    static List<Integer> violations(Pattern pattern, String code) {
        List<Integer> lines = new ArrayList<>();
        Matcher m = pattern.matcher(code);
        while (m.find()) {
            lines.add(lineOf(code, m.start()));
        }
        return lines;
    }

    private static int lineOf(String s, int offset) {
        int line = 1;
        for (int i = 0; i < offset; i++) {
            if (s.charAt(i) == '\n') {
                line++;
            }
        }
        return line;
    }

    /** 去掉 {@code //} 与块注释（换行保留，行号不漂移）；字符串 / 文本块 / 字符字面量原样保留。 */
    static String stripComments(String src) {
        StringBuilder out = new StringBuilder(src.length());
        int i = 0;
        int n = src.length();
        while (i < n) {
            char c = src.charAt(i);
            if (c == '/' && i + 1 < n && src.charAt(i + 1) == '/') {
                while (i < n && src.charAt(i) != '\n') {
                    i++;
                }
            } else if (c == '/' && i + 1 < n && src.charAt(i + 1) == '*') {
                i += 2;
                while (i < n && !(src.charAt(i) == '*' && i + 1 < n && src.charAt(i + 1) == '/')) {
                    if (src.charAt(i) == '\n') {
                        out.append('\n');
                    }
                    i++;
                }
                i = Math.min(n, i + 2);
            } else if (src.startsWith("\"\"\"", i)) {
                int end = src.indexOf("\"\"\"", i + 3);
                end = end < 0 ? n : end + 3;
                out.append(src, i, end);
                i = end;
            } else if (c == '"' || c == '\'') {
                int end = endOfQuoted(src, i, c);
                out.append(src, i, end);
                i = end;
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }

    /** 文件里全部字符串字面量与文本块的内容（注释里的引号不算）。 */
    static List<String> stringLiterals(String src) {
        List<String> out = new ArrayList<>();
        int i = 0;
        int n = src.length();
        while (i < n) {
            char c = src.charAt(i);
            if (c == '/' && i + 1 < n && src.charAt(i + 1) == '/') {
                while (i < n && src.charAt(i) != '\n') {
                    i++;
                }
            } else if (c == '/' && i + 1 < n && src.charAt(i + 1) == '*') {
                int end = src.indexOf("*/", i + 2);
                i = end < 0 ? n : end + 2;
            } else if (src.startsWith("\"\"\"", i)) {
                int end = src.indexOf("\"\"\"", i + 3);
                int stop = end < 0 ? n : end;
                out.add(src.substring(i + 3, stop));
                i = end < 0 ? n : end + 3;
            } else if (c == '"') {
                int end = endOfQuoted(src, i, '"');
                out.add(src.substring(i + 1, Math.max(i + 1, end - 1)));
                i = end;
            } else if (c == '\'') {
                i = endOfQuoted(src, i, '\'');
            } else {
                i++;
            }
        }
        return out;
    }

    /** 从开引号位置找到闭引号之后的下标（处理反斜杠转义；遇换行视为结束，防坏输入跑飞）。 */
    private static int endOfQuoted(String src, int start, char quote) {
        int i = start + 1;
        int n = src.length();
        while (i < n) {
            char c = src.charAt(i);
            if (c == '\\') {
                i += 2;
                continue;
            }
            if (c == quote) {
                return i + 1;
            }
            if (c == '\n') {
                return i;
            }
            i++;
        }
        return n;
    }
}
