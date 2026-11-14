package com.tailtopia.healthreminder.service;

import java.time.LocalDate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** {@code health_vet_consult_visits}（兽医问诊到访，体检 base date 来源，V1.5.0 Story 2.2，架构 Z-8 / §1.4）。 */
@Repository
public class VetConsultVisitDao {

    private final JdbcTemplate jdbc;

    public VetConsultVisitDao(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 写一行（幂等）。到访日 = 会话结束时刻（{@code COALESCE(pending_close_started_at, updated_at)}，与回填同口径）的
     * WIB 日期；会话行读不到 → {@code fallbackDate}。宠物已删 → 不写。@return 是否新写入。
     */
    public boolean record(long sessionId, long petId, LocalDate fallbackDate) {
        return jdbc.update("INSERT INTO health_vet_consult_visits (session_id, pet_profile_id, visit_date) "
                + "SELECT ?, p.id, COALESCE((SELECT (COALESCE(s.pending_close_started_at, s.updated_at) "
                + "AT TIME ZONE 'Asia/Jakarta')::date FROM consult_sessions s WHERE s.id = ?), ?) "
                + "FROM pet_profiles p WHERE p.id = ? ON CONFLICT (session_id) DO NOTHING",
                sessionId, sessionId, fallbackDate, petId) == 1;
    }

    public LocalDate lastVisitDate(long petId) {
        return jdbc.queryForObject("SELECT max(visit_date) FROM health_vet_consult_visits WHERE pet_profile_id = ?",
                LocalDate.class, petId);
    }

    public void deleteForPet(long petId) {
        jdbc.update("DELETE FROM health_vet_consult_visits WHERE pet_profile_id = ?", petId);
    }
}
