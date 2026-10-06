package com.tailtopia.passport.service;

import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 场所合并时的登机牌改挂（V1.3.2 Story 3.5 · AC7 · AD-7）。由 {@code PlaceMergeService.merge} 在打卡改挂之后、
 * {@code markMerged} 之前<b>同一事务同步调用</b>（{@code MANDATORY}）—— 不走 {@code PlaceMergedEvent} 监听：
 * 提交到监听执行之间的窗口里用户可对保留方再买一张（两个 place_id 各一行不冲突），造成不可见的重复付款。
 *
 * <p>对被合并方每一行（逐宠物）：
 * <ol>
 *   <li>保留方无同宠物行 → 被合并方行 {@code place_id} 改为保留方；</li>
 *   <li>被合并方未解锁、保留方有行 → 被合并方行 supersede（在途付款到账时由发放口转到保留方行）；</li>
 *   <li>被合并方已解锁、保留方有行未解锁 → 解锁时刻转给保留方行；被合并方行 supersede 且清空 {@code unlocked_at}（不需退款）；</li>
 *   <li>两边都已解锁 → 被合并方行 supersede、<b>保留</b> {@code unlocked_at} → 后台可筛出「付过两次」人工退款。</li>
 * </ol>
 * superseded 行不删：它可能挂着 PENDING / PAID 购买（{@code ref_id} 指向它）。
 */
@Service
public class BoardingPassMergeService {

    private final NamedParameterJdbcTemplate jdbc;

    public BoardingPassMergeService(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** @return 改挂 + supersede 的行数（进合并审计串 {@code boardingPasses=}） */
    @Transactional(propagation = Propagation.MANDATORY)
    public int reassignForMerge(long mergedPlaceId, long keepPlaceId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT id, pet_profile_id, unlocked_at FROM boarding_pass_unlocks
                 WHERE place_id = :merged AND superseded_at IS NULL ORDER BY id FOR UPDATE""",
                Map.of("merged", mergedPlaceId));
        int changed = 0;
        for (Map<String, Object> r : rows) {
            long id = ((Number) r.get("id")).longValue();
            long petId = ((Number) r.get("pet_profile_id")).longValue();
            boolean mergedUnlocked = r.get("unlocked_at") != null;
            List<Map<String, Object>> keep = jdbc.queryForList("""
                    SELECT id, unlocked_at FROM boarding_pass_unlocks
                     WHERE pet_profile_id = :pet AND place_id = :keep FOR UPDATE""",
                    Map.of("pet", petId, "keep", keepPlaceId));
            if (keep.isEmpty()) {
                changed += jdbc.update("UPDATE boarding_pass_unlocks SET place_id = :keep WHERE id = :id",
                        Map.of("keep", keepPlaceId, "id", id));
                continue;
            }
            long keepId = ((Number) keep.get(0).get("id")).longValue();
            boolean keepUnlocked = keep.get(0).get("unlocked_at") != null;
            if (mergedUnlocked && !keepUnlocked) {
                jdbc.update("""
                        UPDATE boarding_pass_unlocks SET unlocked_at = (SELECT unlocked_at FROM boarding_pass_unlocks
                         WHERE id = :from) WHERE id = :to""", Map.of("from", id, "to", keepId));
                changed += jdbc.update("UPDATE boarding_pass_unlocks SET superseded_at = now(), unlocked_at = NULL "
                        + "WHERE id = :id", Map.of("id", id));
            } else {
                // 未解锁（在途付款到账由发放口转过去）/ 两边都已解锁（保留 unlocked_at 作退款候选）
                changed += jdbc.update("UPDATE boarding_pass_unlocks SET superseded_at = now() WHERE id = :id",
                        Map.of("id", id));
            }
        }
        return changed;
    }
}
