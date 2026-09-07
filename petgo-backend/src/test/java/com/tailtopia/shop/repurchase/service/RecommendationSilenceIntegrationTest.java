package com.tailtopia.shop.repurchase.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.tailtopia.profile.domain.ArchiveDecision;
import com.tailtopia.profile.domain.HealthSourceType;
import com.tailtopia.profile.dto.ArchiveDecisionRequest;
import com.tailtopia.profile.dto.HealthRecordCreateRequest;
import com.tailtopia.profile.repository.PetProfileRepository;
import com.tailtopia.profile.service.HealthEventService;
import com.tailtopia.profile.service.HealthRecordService;
import com.tailtopia.support.ApiIntegrationTest;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * L1：推荐静默期（V1.4.0 · 设计文档 03 屏 5 第 4 块）。
 *
 * <p>这条规则的价值全在<b>边界</b>：设计要的是「事件前后各 7 天」，写成 6 天或 8 天在
 * 功能上都「能跑」，只有逐日断言能分辨。故本类的主体是第 7 天 / 第 8 天的一天之差。
 *
 * <p>🔴 <b>造数一律走真实 service</b>（{@link HealthRecordService} /
 * {@link HealthEventService}），不直接 INSERT —— 直接插表会绕过
 * {@code @PastOrPresent} 这类校验，测出来的边界和线上不是同一个边界。
 * 唯一例外是 users / pet_profiles 两张表，沿用本包 {@code RepurchaseIntegrationTest} 的既有做法。
 */
class RecommendationSilenceIntegrationTest extends ApiIntegrationTest {

    @Autowired
    private RecommendationSilenceService silence;
    @Autowired
    private HealthRecordService healthRecords;
    @Autowired
    private HealthEventService healthEvents;
    @Autowired
    private PetProfileRepository profiles;
    @Autowired
    private JdbcTemplate jdbc;

    private static final LocalDate TODAY = LocalDate.of(2026, 8, 19);

    // ---------- 造数 ----------

    private long seedUser() {
        long n = SEQ.incrementAndGet();
        jdbc.update("INSERT INTO users (nickname, status) VALUES (?, 'ACTIVE')", "sil" + n);
        return jdbc.queryForObject("SELECT id FROM users WHERE nickname = ?", Long.class,
                "sil" + n);
    }

    private long seedPet(long ownerId) {
        jdbc.update("""
                INSERT INTO pet_profiles (owner_id, pet_type, name, birthday, card_token,
                        weight_kg, created_at, updated_at)
                VALUES (?, 'CAT', 'Miko', ?, ?, ?, now(), now())
                """, ownerId, LocalDate.of(2025, 12, 1), "sc" + SEQ.incrementAndGet(),
                new BigDecimal("3.4"));
        return profiles.findByOwnerId(ownerId).orElseThrow().getId();
    }

    /** 记一条绝育（= 目前唯一能落库的「手术」类型）。 */
    private void seedSurgery(long ownerId, LocalDate on) {
        healthRecords.create(ownerId,
                new HealthRecordCreateRequest("NEUTER", null, null, on, null));
    }

    /** 存档一条问诊（= 「生病」的代理信号）。 */
    private void seedArchivedConsult(long ownerId, long petId, LocalDate on, String aiLevel) {
        healthEvents.recordDecision(ownerId, new ArchiveDecisionRequest(
                HealthSourceType.AI_TRIAGE, "sil-ref-" + SEQ.incrementAndGet(), petId,
                ArchiveDecision.ARCHIVED, on, "batuk", aiLevel, "istirahat", List.of()));
    }

    // ---------- 边界：这组是本类存在的理由 ----------

    @Test
    @DisplayName("手术当天静默")
    void surgeryOnTheDaySilences() {
        long uid = seedUser();
        long pid = seedPet(uid);
        seedSurgery(uid, TODAY);

        assertThat(silence.isSilencedForPet(pid, TODAY)).isTrue();
    }

    @Test
    @DisplayName("手术后第 7 天仍静默，第 8 天恢复 —— 一天之差")
    void silenceEndsExactlyAfterSevenDays() {
        long uid = seedUser();
        long pid = seedPet(uid);
        seedSurgery(uid, TODAY.minusDays(7));

        // 第 7 天：记录日 + 7 = 今天，仍在窗口内
        assertThat(silence.isSilencedForPet(pid, TODAY))
                .as("手术后第 7 天必须仍然静默")
                .isTrue();
        // 第 8 天：越界，恢复推荐
        assertThat(silence.isSilencedForPet(pid, TODAY.plusDays(1)))
                .as("手术后第 8 天必须恢复 —— 写成 >7 还是 >=7 就差在这一条")
                .isFalse();
    }

    @Test
    @DisplayName("窗口向前也成立：未来 7 天内的问诊事件使今天静默")
    void futureEventWithinWindowAlsoSilences() {
        long uid = seedUser();
        long pid = seedPet(uid);
        // 🔴 只有健康事件能落未来日期（健康记录被 @PastOrPresent 挡住）。
        //    这条用例锁住「前后各 7 天」的前半边确实生效，而不是只写在注释里。
        seedArchivedConsult(uid, pid, TODAY.plusDays(7), "YELLOW");

        assertThat(silence.isSilencedForPet(pid, TODAY)).isTrue();
        assertThat(silence.isSilencedForPet(pid, TODAY.minusDays(1)))
                .as("再往前一天就该越界")
                .isFalse();
    }

    // ---------- 数据源：谁算负面事件、谁不算 ----------

    @Test
    @DisplayName("已存档问诊算负面事件（GREEN 也算 —— 判定刻意从宽）")
    void archivedConsultSilencesEvenWhenGreen() {
        long uid = seedUser();
        long pid = seedPet(uid);
        seedArchivedConsult(uid, pid, TODAY.minusDays(2), "GREEN");

        // 误静默 = 少几次曝光；漏静默 = 在坏消息旁边卖东西。两者代价不对称，故取保守侧。
        assertThat(silence.isSilencedForPet(pid, TODAY)).isTrue();
    }

    @Test
    @DisplayName("疫苗与驱虫是常规保健，不触发静默 —— 否则静默期几乎常驻")
    void routineCareDoesNotSilence() {
        long uid = seedUser();
        long pid = seedPet(uid);
        healthRecords.create(uid,
                new HealthRecordCreateRequest("VACCINE", null, "Rabies", TODAY.minusDays(1), null));
        healthRecords.create(uid,
                new HealthRecordCreateRequest("DEWORM", null, null, TODAY.minusDays(1), null));

        assertThat(silence.isSilencedForPet(pid, TODAY))
                .as("把常规保健算进负面事件，会让这条规则失去意义")
                .isFalse();
    }

    @Test
    @DisplayName("SKIPPED 的问诊不算 —— 用户没把它存进健康档案")
    void skippedConsultDoesNotSilence() {
        long uid = seedUser();
        long pid = seedPet(uid);
        healthEvents.recordDecision(uid, new ArchiveDecisionRequest(
                HealthSourceType.AI_TRIAGE, "sil-skip-" + SEQ.incrementAndGet(), pid,
                ArchiveDecision.SKIPPED, TODAY.minusDays(1), null, "GREEN", null, List.of()));

        assertThat(silence.isSilencedForPet(pid, TODAY)).isFalse();
    }

    // ---------- 隔离与降级 ----------

    @Test
    @DisplayName("静默按宠物隔离：别人家宠物做手术不影响我")
    void silenceIsScopedToOnePet() {
        long otherOwner = seedUser();
        long otherPet = seedPet(otherOwner);
        seedSurgery(otherOwner, TODAY);

        long uid = seedUser();
        long pid = seedPet(uid);

        assertThat(silence.isSilencedForPet(otherPet, TODAY)).isTrue();
        assertThat(silence.isSilencedForPet(pid, TODAY))
                .as("静默判定漏了 petProfileId 过滤的话，这条会红")
                .isFalse();
    }

    @Test
    @DisplayName("无健康记录 → 不静默（默认放行，不是默认拦截）")
    void noRecordsMeansNoSilence() {
        long uid = seedUser();
        long pid = seedPet(uid);

        assertThat(silence.isSilencedForPet(pid, TODAY)).isFalse();
    }

    @Test
    @DisplayName("未建档用户不静默 —— 没有宠物就没有健康事件")
    void userWithoutProfileIsNotSilenced() {
        long uid = seedUser();

        assertThat(silence.isSilenced(uid)).isFalse();
    }
}
