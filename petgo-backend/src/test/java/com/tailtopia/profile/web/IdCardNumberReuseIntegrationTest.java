package com.tailtopia.profile.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tailtopia.auth.domain.User;
import com.tailtopia.profile.dto.CreateIdCardRequest;
import com.tailtopia.profile.dto.IdCardCreateResponse;
import com.tailtopia.profile.service.IdCardService;
import com.tailtopia.support.ApiIntegrationTest;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;

/**
 * L1（V1.3.4 ktp Story 2.3，AD-5）：已解锁学生卡的编号沿用——真库、走建卡接口。需本分支专用库（云端不跑）。
 *
 * <p>来源卡用 JDBC 直接造（置 hd_unlocked / hd_unlocked_at），号码用远离计数器真实号段的值（年段 99），
 * 归属行手工登记，避免与同库其他用例撞号。
 */
class IdCardNumberReuseIntegrationTest extends ApiIntegrationTest {

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private IdCardService idCardService;

    private static final String BIRTHDAY = "2024-03-10";

    private String body(String cardType, String variant, String gender, String petType, String birthday) {
        return "{\"name\":\"Momo\",\"petType\":\"" + petType + "\",\"breed\":\"Domestik\",\"birthday\":\"" + birthday
                + "\",\"gender\":\"" + gender + "\",\"cardType\":\"" + cardType + "\""
                + (variant == null ? "" : ",\"cardVariant\":\"" + variant + "\"") + "}";
    }

    private JsonNode create(long uid, String body) throws Exception {
        String res = mvc.perform(post("/api/v1/pet-profiles/me/id-cards")
                        .header(HttpHeaders.AUTHORIZATION, userBearer(uid))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(res);
    }

    private String uniq(String prefix, int width) {
        return prefix + String.format("%0" + width + "d", SEQ.incrementAndGet() % (long) Math.pow(10, width));
    }

    /** 测试身份码：14 位，取值空间 1e8，远离计数器真实号段（年段 99），跨次运行几乎不撞。 */
    private String testCardNo() {
        return uniq("TT6099", 8);
    }

    /** 测试护照号：12 位，年段 9x、取值空间 1e6。 */
    private String testPassportNo(String sp) {
        return uniq("TT" + sp + "P9", 6);
    }

    /** JDBC 造一张卡（可指定卡种 / 解锁 / 解锁时刻 / 号），并按需登记归属。 */
    private long sourceCard(long uid, String type, String variant, boolean unlocked, Instant unlockedAt,
            String gender, String petType, String birthday, String cardNo, String passportNo, boolean claim) {
        long id = jdbc.queryForObject("""
                INSERT INTO id_cards (user_id, serial_id, name, pet_type, birthday, gender, card_no, passport_no,
                                      card_type, card_variant, hd_unlocked, hd_unlocked_at)
                VALUES (?, ?, 'Momo', ?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING id
                """, Long.class, uid, 9_000_000_000L + SEQ.incrementAndGet() % 1_000_000_000L, petType,
                java.sql.Date.valueOf(birthday), gender, cardNo, passportNo, type, variant, unlocked,
                unlockedAt == null ? null : Timestamp.from(unlockedAt));
        if (claim) {
            if (cardNo != null) {
                jdbc.update("INSERT INTO id_card_number_owners (kind, number, user_id) VALUES ('C', ?, ?)", cardNo, uid);
            }
            if (passportNo != null) {
                jdbc.update("INSERT INTO id_card_number_owners (kind, number, user_id) VALUES ('P', ?, ?)", passportNo, uid);
            }
        }
        return id;
    }

    private record Source(long id, String cardNo, String passportNo) {
    }

    private Source unlockedStudent(long uid, String gender, String petType, Instant at) {
        String c = testCardNo();
        String p = testPassportNo("02");
        long id = sourceCard(uid, "STUDENT", "BINTANG", true, at, gender, petType, BIRTHDAY, c, p, true);
        return new Source(id, c, p);
    }

    @Test
    void hitCopiesNumbersForAnyStyleAndKeepsNewCardLocked() throws Exception {
        User u = newUser();
        Source src = unlockedStudent(u.getId(), "FEMALE", "CAT", Instant.parse("2026-09-01T00:00:00Z"));

        JsonNode ktp = create(u.getId(), body("KTP", null, "FEMALE", "CAT", BIRTHDAY));
        assertThat(ktp.get("cardNo").asString()).isEqualTo(src.cardNo());
        assertThat(ktp.get("passportNo").asString()).isEqualTo(src.passportNo());
        assertThat(ktp.get("numberReused").asBoolean()).isTrue();
        assertThat(ktp.get("hdUnlocked").asBoolean()).isFalse();
        long srcSerial = jdbc.queryForObject("SELECT serial_id FROM id_cards WHERE id = ?", Long.class, src.id());
        assertThat(ktp.get("serialId").asLong()).isNotEqualTo(srcSerial);

        JsonNode sameStyle = create(u.getId(), body("STUDENT", "BINTANG", "FEMALE", "CAT", BIRTHDAY));
        assertThat(sameStyle.get("cardNo").asString()).isEqualTo(src.cardNo());
        assertThat(sameStyle.get("numberReused").asBoolean()).isTrue();
        assertThat(jdbc.queryForObject("SELECT hd_unlocked_at FROM id_cards WHERE id = ?", Timestamp.class,
                sameStyle.get("id").asLong())).isNull();
    }

    @Test
    void anyOfThreeDifferentAllocatesNew() throws Exception {
        User u = newUser();
        Source src = unlockedStudent(u.getId(), "MALE", "CAT", Instant.parse("2026-09-01T00:00:00Z"));
        for (String b : List.of(
                body("KTP", null, "MALE", "CAT", "2024-03-11"),   // 生日不同
                body("KTP", null, "UNKNOWN", "CAT", BIRTHDAY),    // UNKNOWN ≠ MALE
                body("KTP", null, "MALE", "DOG", BIRTHDAY))) {    // 物种不同
            JsonNode n = create(u.getId(), b);
            assertThat(n.get("cardNo").asString()).isNotEqualTo(src.cardNo());
            assertThat(n.get("numberReused").asBoolean()).isFalse();
        }
    }

    @Test
    void nonQualifyingSourcesAreIgnored() throws Exception {
        User u = newUser();
        Instant at = Instant.parse("2026-09-01T00:00:00Z");
        // 未解锁学生卡
        sourceCard(u.getId(), "STUDENT", "CERIA", false, null, "FEMALE", "CAT", BIRTHDAY,
                testCardNo(), testPassportNo("02"), true);
        // V95 前旧卡（无号）
        sourceCard(u.getId(), "STUDENT", "KLASIK", true, at, "FEMALE", "CAT", BIRTHDAY, null, null, false);
        // 已解锁但是 KTP
        String ktpNo = testCardNo();
        sourceCard(u.getId(), "KTP", null, true, at, "FEMALE", "CAT", BIRTHDAY, ktpNo, testPassportNo("02"), true);
        // D-19：快照物种为空的老学生卡不与 OTHER 视为一致
        String nullTypeNo = testCardNo();
        sourceCard(u.getId(), "STUDENT", "KLASIK", true, at, "FEMALE", null, BIRTHDAY, nullTypeNo,
                testPassportNo("00"), true);

        JsonNode n = create(u.getId(), body("SIM", null, "FEMALE", "CAT", BIRTHDAY));
        assertThat(n.get("numberReused").asBoolean()).isFalse();
        assertThat(n.get("cardNo").asString()).isNotEqualTo(ktpNo);
        JsonNode other = create(u.getId(), body("SIM", null, "FEMALE", "OTHER", BIRTHDAY));
        assertThat(other.get("numberReused").asBoolean()).isFalse();
        assertThat(other.get("cardNo").asString()).isNotEqualTo(nullTypeNo);
    }

    @Test
    void earliestUnlockedWinsThenSmallerId() throws Exception {
        User u = newUser();
        // 较晚解锁的那张 id 更小
        Source late = unlockedStudent(u.getId(), "FEMALE", "CAT", Instant.parse("2026-09-05T00:00:00Z"));
        Source early = unlockedStudent(u.getId(), "FEMALE", "CAT", Instant.parse("2026-09-01T00:00:00Z"));
        assertThat(late.id()).isLessThan(early.id());
        assertThat(create(u.getId(), body("KTP", null, "FEMALE", "CAT", BIRTHDAY)).get("cardNo").asString())
                .isEqualTo(early.cardNo());

        User v = newUser();
        Instant same = Instant.parse("2026-09-03T00:00:00Z");
        Source first = unlockedStudent(v.getId(), "MALE", "DOG", same);
        unlockedStudent(v.getId(), "MALE", "DOG", same);
        assertThat(create(v.getId(), body("KTP", null, "MALE", "DOG", BIRTHDAY)).get("cardNo").asString())
                .as("同刻按 id 小").isEqualTo(first.cardNo());
    }

    @Test
    void concurrentCreatesAgreeOnReuse() throws Exception {
        User u = newUser();
        Source src = unlockedStudent(u.getId(), "FEMALE", "CAT", Instant.parse("2026-09-01T00:00:00Z"));
        List<IdCardCreateResponse> withSource = concurrently(u.getId(), "CAT");
        assertThat(withSource).allSatisfy(r -> {
            assertThat(r.numberReused()).isTrue();
            assertThat(r.card().cardNo()).isEqualTo(src.cardNo());
        });

        User w = newUser();
        List<IdCardCreateResponse> noSource = concurrently(w.getId(), "DOG");
        assertThat(noSource).allSatisfy(r -> assertThat(r.numberReused()).isFalse());
        assertThat(noSource.get(0).card().cardNo()).isNotEqualTo(noSource.get(1).card().cardNo());
    }

    private List<IdCardCreateResponse> concurrently(long uid, String petType) throws Exception {
        CreateIdCardRequest req = new CreateIdCardRequest("Momo", petType, "Domestik", LocalDate.parse(BIRTHDAY),
                "FEMALE", null, null, null, null, null, null, "KTP", null, null, null);
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<IdCardCreateResponse>> fs = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                fs.add(pool.submit(() -> {
                    go.await();
                    return idCardService.createCard(uid, req);
                }));
            }
            go.countDown();
            List<IdCardCreateResponse> out = new ArrayList<>();
            for (Future<IdCardCreateResponse> f : fs) {
                out.add(f.get(30, TimeUnit.SECONDS));
            }
            return out;
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void reusedNumberOwnedByAnotherUserRollsBackWith500() throws Exception {
        User a = newUser();
        User b = newUser();
        String c = testCardNo();
        String p = testPassportNo("02");
        sourceCard(a.getId(), "STUDENT", "MALAM", true, Instant.parse("2026-09-01T00:00:00Z"), "FEMALE", "CAT",
                BIRTHDAY, c, p, false); // 不登记归属
        jdbc.update("INSERT INTO id_card_number_owners (kind, number, user_id) VALUES ('C', ?, ?)", c, b.getId());
        long before = jdbc.queryForObject("SELECT count(*) FROM id_cards WHERE user_id = ?", Long.class, a.getId());

        mvc.perform(post("/api/v1/pet-profiles/me/id-cards")
                        .header(HttpHeaders.AUTHORIZATION, userBearer(a.getId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("KTP", null, "FEMALE", "CAT", BIRTHDAY)))
                .andExpect(status().isInternalServerError());

        assertThat(jdbc.queryForObject("SELECT count(*) FROM id_cards WHERE user_id = ?", Long.class, a.getId()))
                .isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM id_card_number_owners WHERE user_id = ? AND number = ?",
                Long.class, a.getId(), c)).isZero();
    }

    @Test
    void sourceOfAnotherUserIsNotUsed() throws Exception {
        User a = newUser();
        User b = newUser();
        Source bSrc = unlockedStudent(b.getId(), "FEMALE", "CAT", Instant.parse("2026-09-01T00:00:00Z"));
        JsonNode n = create(a.getId(), body("KTP", null, "FEMALE", "CAT", BIRTHDAY));
        assertThat(n.get("numberReused").asBoolean()).isFalse();
        assertThat(n.get("cardNo").asString()).isNotEqualTo(bSrc.cardNo());
    }
}
