package com.tailtopia.growth.service;

import com.tailtopia.growth.domain.FrameChoice;
import com.tailtopia.growth.domain.GrowthAccount;
import com.tailtopia.growth.domain.GrowthLevelDef;
import com.tailtopia.growth.domain.GrowthTier;
import com.tailtopia.growth.repository.GrowthAccountRepository;
import com.tailtopia.growth.repository.GrowthLevelDefRepository;
import com.tailtopia.growth.web.dto.GrowthPageResponse.Frames;
import com.tailtopia.shared.error.AppException;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 用户选头像框（V1.4.0 ① Story 3.3 · AD-16 / AD-10）。🔴 {@code frame_choice} 只经这里写。
 *
 * <p>同一事务：参与判定 → 懒建账户行并 {@code FOR UPDATE} 锁住 → 按<b>当前等级</b>的段位算可用档 → {@code NONE} 恒允许、
 * 不可用 → 409 {@code frame-locked}（列不变）→ 与现值不同才写。不写成长值流水、不改等级、不发事件。
 * 选过任何框（含当前自动生效那档、含 {@code NONE}）后 {@code frame_choice} 非空 → 此后升级不再自动换框。
 */
@Service
public class GrowthFrameService {

    static final String PARTICIPATING_SQL =
            "SELECT " + GrowthParticipation.ACTIVE_REGULAR_USER + " AS participating FROM users u WHERE u.id = :u";

    private static final Logger log = LoggerFactory.getLogger(GrowthFrameService.class);

    private final NamedParameterJdbcTemplate jdbc;
    private final GrowthAccountRepository accounts;
    private final GrowthLevelDefRepository levels;
    private final Clock clock;

    @Autowired
    public GrowthFrameService(NamedParameterJdbcTemplate jdbc, GrowthAccountRepository accounts,
            GrowthLevelDefRepository levels) {
        this(jdbc, accounts, levels, Clock.systemUTC());
    }

    GrowthFrameService(NamedParameterJdbcTemplate jdbc, GrowthAccountRepository accounts,
            GrowthLevelDefRepository levels, Clock clock) {
        this.jdbc = jdbc;
        this.accounts = accounts;
        this.levels = levels;
        this.clock = clock;
    }

    /** 返回与 {@code GET /me/growth} 同一个头像框四档 DTO。 */
    @Transactional
    public Frames choose(long userId, FrameChoice requested) {
        if (requested == null) {
            throw AppException.validation("frame is required");
        }
        List<Map<String, Object>> rows = jdbc.queryForList(PARTICIPATING_SQL, Map.of("u", userId));
        if (rows.isEmpty() || !Boolean.TRUE.equals(rows.get(0).get("participating"))) {
            // 不参与成长值（虚拟号 / 官方号 / 停用 / 注销中）：没有可选的框，也不建账户行。
            throw AppException.frameLocked("user does not take part in growth levels");
        }
        accounts.insertIfAbsent(userId);
        GrowthAccount account = accounts.lockById(userId)
                .orElseThrow(() -> new IllegalStateException("growth account missing after insertIfAbsent"));
        List<GrowthLevelDef> table = levels.findAllByOrderByLevelAsc();
        GrowthTier tier = GrowthPageQuery.position(table, account.getLevel()).tier();
        if (requested != FrameChoice.NONE && !GrowthFrameResolver.availableFrames(tier).contains(requested)) {
            throw AppException.frameLocked("frame " + requested + " is not unlocked at the current tier");
        }
        if (requested != account.getFrameChoice()) {
            account.chooseFrame(requested, clock.instant());
            log.info("growth frame chosen: userId={} frame={}", userId, requested);
        }
        return GrowthPageQuery.frames(table, tier, requested);
    }
}
