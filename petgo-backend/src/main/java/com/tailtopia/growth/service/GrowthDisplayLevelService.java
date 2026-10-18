package com.tailtopia.growth.service;

import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.IntSupplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 虚拟号 / 官方号的展示等级（V1.4.0 ① Story 2.2 · AD-13 · P-7）。展示等级只用于对外展示（角标 / 头像框），
 * 不参与成长值、不享任何权益。
 *
 * <p>分工：本 story 只有 {@link #assignInitial}（新建虚拟号时随机 6 / 7 / 8）；运营修改展示等级（含审计）由 Story 4.4
 * 在本类追加 {@code set} 方法。存量虚拟号 / 官方号由迁移 {@code V20261018_1503} 一次性分配。
 *
 * <p>写入纪律（§5.4）：只写 {@code display_level}，不碰累计、等级与流水。
 */
@Service
public class GrowthDisplayLevelService {

    static final int MIN_INITIAL = 6;
    static final int SPAN = 3;

    static final String ASSIGN_SQL = "INSERT INTO growth_accounts (user_id, lifetime_total, balance, level, "
            + "display_level, backfill_popup_pending, version, created_at, updated_at) "
            + "VALUES (:u, 0, 0, 1, :d, false, 0, now(), now()) ON CONFLICT (user_id) DO NOTHING";

    private final NamedParameterJdbcTemplate jdbc;
    private final IntSupplier random;

    @Autowired
    public GrowthDisplayLevelService(NamedParameterJdbcTemplate jdbc) {
        this(jdbc, () -> ThreadLocalRandom.current().nextInt(SPAN));
    }

    /** {@code random} 返回 [0, 3) 的整数。 */
    GrowthDisplayLevelService(NamedParameterJdbcTemplate jdbc, IntSupplier random) {
        this.jdbc = jdbc;
        this.random = random;
    }

    /** 在调用方事务内为新建的虚拟号建账户行，随机展示等级 6–8；已有行不覆盖。 */
    @Transactional
    public void assignInitial(long virtualUserId) {
        int level = MIN_INITIAL + Math.floorMod(random.getAsInt(), SPAN);
        jdbc.update(ASSIGN_SQL, Map.of("u", virtualUserId, "d", level));
    }
}
