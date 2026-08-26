package com.tailtopia.profile.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tailtopia.auth.domain.User;
import com.tailtopia.profile.domain.ArchiveDecision;
import com.tailtopia.profile.domain.HealthEvent;
import com.tailtopia.profile.domain.HealthSourceType;
import com.tailtopia.profile.domain.PetProfile;
import com.tailtopia.profile.repository.HealthEventRepository;
import com.tailtopia.profile.repository.PetProfileRepository;
import com.tailtopia.support.ApiIntegrationTest;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * L1：问诊存档按<b>就诊日期</b>落位（2026-08-18 修）。
 *
 * <h2>修的是什么</h2>
 * 问诊存档有两个时间：<b>就诊那天</b>（{@code event_date}）与<b>归档进档案的时刻</b>
 * （{@code created_at}）。用户可以把一次三个月前的问诊今天才存进档案 —— 两者能差三个月。
 *
 * <p>改之前，日历、某天详情、时间线读的都是<b>归档时刻</b>，于是那次问诊显示在「今天」。
 * <b>数据本身一直是对的</b>（就诊日期是 NOT NULL、一直存着），是取数读错了字段。
 *
 * <h2>为什么三处必须一起改</h2>
 * 只改日历会让日历说「5 月 10 日」、时间线说「今天」 —— 同一条记录两个日期，更难解释。
 * 而时间线的排序键与游标键是统一的复合锚点，取数也必须跟着换成就诊日期，
 * 否则就是「排序一把尺、翻页另一把尺」，跨页时条目会丢失或重复
 * （成长内容早先正是踩了这个坑才做的锚点重构）。
 */
class ConsultArchiveEventDateTest extends ApiIntegrationTest {

    /** 就诊日：三个月前。 */
    private static final LocalDate VISITED_ON = LocalDate.of(2026, 5, 10);

    @Autowired
    private PetProfileRepository profiles;

    @Autowired
    private HealthEventRepository healthEvents;

    @Autowired
    private JdbcTemplate jdbc;

    private PetProfile createProfile(User owner) throws Exception {
        mvc.perform(post("/api/v1/pet-profiles")
                        .header(HttpHeaders.AUTHORIZATION, userBearer(owner.getId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"petType":"CAT","name":"Momo","birthday":"2024-03-10"}
                                """))
                .andExpect(status().isCreated());
        return profiles.findByOwnerId(owner.getId()).orElseThrow();
    }

    /**
     * 造一次「三个月前就诊、<b>今天才归档</b>」的问诊存档 —— 正是会暴露这个缺陷的那种数据。
     */
    private void seedBackdatedConsult(PetProfile pet) {
        HealthEvent e = healthEvents.save(HealthEvent.archived(
                pet.getId(), HealthSourceType.AI_TRIAGE, "ref-" + SEQ.incrementAndGet(),
                VISITED_ON, "咳嗽", "YELLOW", "多喝水", List.of()));
        // created_at 由 @PrePersist 置为「现在」，正是"今天才归档"。显式确认一下，
        // 免得将来实体改成用 event_date 填 created_at 时这条用例悄悄失去意义。
        Object createdDay = jdbc.queryForObject(
                "SELECT created_at::date FROM health_events WHERE id = ?", Object.class, e.getId());
        assertThat(createdDay.toString())
                .as("夹具必须是「就诊日 ≠ 归档日」，否则这条用例什么也证明不了")
                .isNotEqualTo(VISITED_ON.toString());
    }

    private String authorCalendar(User owner, int year, int month) throws Exception {
        return mvc.perform(get("/api/v1/pet-profiles/me/calendar")
                        .header(HttpHeaders.AUTHORIZATION, userBearer(owner.getId()))
                        .param("year", String.valueOf(year)).param("month", String.valueOf(month)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    /** 🔴 日历：落在<b>就诊那天</b>，而不是归档那天。 */
    @Test
    void calendarPlacesConsultOnTheVisitDateNotTheArchiveDate() throws Exception {
        User owner = newUser();
        PetProfile pet = createProfile(owner);
        seedBackdatedConsult(pet);

        String visitMonth = authorCalendar(owner, VISITED_ON.getYear(), VISITED_ON.getMonthValue());
        assertThat(visitMonth)
                .as("问诊存档应落在就诊那天（%s）—— 数据里一直有就诊日期，读错字段才会跑到今天",
                        VISITED_ON)
                .contains("\"day\":" + VISITED_ON.getDayOfMonth())
                .contains("\"hasHealthEvent\":true");

        LocalDate today = LocalDate.now();
        if (today.getMonthValue() != VISITED_ON.getMonthValue()
                || today.getYear() != VISITED_ON.getYear()) {
            String thisMonth = authorCalendar(owner, today.getYear(), today.getMonthValue());
            assertThat(thisMonth)
                    .as("归档那天（今天）不该冒出一个问诊标记")
                    .doesNotContain("\"hasHealthEvent\":true");
        }
    }

    /** 🔴 某天详情：在就诊那天能查到，在归档那天查不到。 */
    @Test
    void dayDetailFindsConsultOnVisitDateOnly() throws Exception {
        User owner = newUser();
        PetProfile pet = createProfile(owner);
        seedBackdatedConsult(pet);

        String onVisitDay = mvc.perform(get("/api/v1/pet-profiles/me/day")
                        .header(HttpHeaders.AUTHORIZATION, userBearer(owner.getId()))
                        .param("date", VISITED_ON.toString()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(onVisitDay).contains("咳嗽");

        String onArchiveDay = mvc.perform(get("/api/v1/pet-profiles/me/day")
                        .header(HttpHeaders.AUTHORIZATION, userBearer(owner.getId()))
                        .param("date", LocalDate.now().toString()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(onArchiveDay)
                .as("归档那天不该出现这条 —— 那天什么也没发生")
                .doesNotContain("咳嗽");
    }

    /** 🔴 时间线：条目带的是就诊日期，客户端据此显示日期与排序。 */
    @Test
    void timelineCarriesTheVisitDateSoItSortsToTheRightPlace() throws Exception {
        User owner = newUser();
        PetProfile pet = createProfile(owner);
        seedBackdatedConsult(pet);

        String json = mvc.perform(get("/api/v1/pet-profiles/me/timeline")
                        .header(HttpHeaders.AUTHORIZATION, userBearer(owner.getId())))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(json).contains("咳嗽");
        assertThat(json)
                .as("问诊条目必须带就诊日期 —— 不带的话客户端会回退到归档时刻，显示成今天")
                .contains("\"eventDate\":\"" + VISITED_ON + "\"");
    }

    /** 统计口径不受影响：问诊「次数」数的是存档条数，与日期无关。 */
    @Test
    void consultCountIsUnaffectedByTheDateFix() throws Exception {
        User owner = newUser();
        PetProfile pet = createProfile(owner);
        seedBackdatedConsult(pet);

        assertThat(healthEvents.countByPetIdAndArchiveDecision(pet.getId(), ArchiveDecision.ARCHIVED))
                .isEqualTo(1);
        assertThat(mvc.perform(get("/api/v1/pet-profiles/me/archive-stats")
                        .header(HttpHeaders.AUTHORIZATION, userBearer(owner.getId())))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString())
                .contains("\"consultCount\":1");
    }
}
