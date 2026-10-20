package com.tailtopia.profile.slot;

import com.tailtopia.profile.slot.domain.PetSlotPurchase;
import com.tailtopia.profile.slot.domain.PetSlotPurchaseKind;
import com.tailtopia.profile.slot.domain.PetSlotRentalSource;
import com.tailtopia.purchase.service.GrantSavepoint;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongToIntFunction;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 月租名额发放口（V1.4.0 multi-pet Story 2.2 · code review #1 / #2，照 {@code KeepsakeGranter} + {@link GrantSavepoint} 范式）。
 *
 * <p>🔴 <b>全部写库走 JDBC、包在保存点里</b>：到账时本方法在 {@code applyCallback} 的事务里跑，任何失败只回滚到保存点，
 * 外层事务不被标 rollback-only，意图 PAID 照常提交、购买行记 ORPHAN_PAID 交人工（{@code PetSlotPaidHandler}）。
 * 保存点内不经任何 {@code @Transactional} 代理 / JPA（它们一抛就把外层标 rollback-only，catch 也救不回）。
 * 等级名额经 {@link PetSlotService#levelSlotsOf} 在<b>独立只读事务</b>（REQUIRES_NEW）里读，失败同样不污染外层。
 *
 * <p>发放规则：
 * <ul>
 *   <li>保存点内先取账号锁（与 {@link PetSlotLock#lockUser} 同一把两参 advisory 锁，可重入）；</li>
 *   <li>RENT：新建 {@code quantity} 行名额（{@code starts_at = paidAt}、+{@code days} 天、{@code source = RENT}）；</li>
 *   <li>ADMIN_GRANT（Story 4.3 运营补发）：同 RENT 新建，{@code source = ADMIN_GRANT}；</li>
 *   <li>RENEW：所选名额仍生效（ACTIVE 且 {@code expires_at > paidAt}）→ {@code expires_at += days}、{@code reminded_at = NULL}；
 *       已过期 → 改为新建一行 RENT 名额（到账不回滚）；</li>
 *   <li>🔴 封顶：会新增名额且 {@code 1 + 等级名额 + 生效名额 + 新增数 > 5} → 不写任何行，返回 {@link Outcome#OVER_CAP}
 *       （不发无用名额，调用方记 OVERPAID 交人工退款 / PawCoin 直接拒）。</li>
 * </ul>
 * 失败原样抛出（调用方决定：到账 → ORPHAN_PAID；PawCoin 同步成交 → 整笔回滚）。日志只在调用方记 id。
 */
@Component
public class PetSlotGranter {

    static final String LOCK_SQL = "SELECT pg_advisory_xact_lock(:ns, :uid)";
    static final String COUNT_LIVE_SQL = "SELECT count(*) FROM pet_slot_rentals"
            + " WHERE user_id = :uid AND status = 'ACTIVE' AND expires_at > :now";
    static final String SELECT_SELECTED_SQL = "SELECT id, status, expires_at FROM pet_slot_rentals"
            + " WHERE user_id = :uid AND id IN (:ids)";
    static final String INSERT_SQL = "INSERT INTO pet_slot_rentals"
            + " (public_token, user_id, source, starts_at, expires_at, status, purchase_id, created_at)"
            + " VALUES (:token, :uid, :source, :startsAt, :expiresAt, 'ACTIVE', :purchaseId, :startsAt) RETURNING id";
    static final String EXTEND_SQL = "UPDATE pet_slot_rentals"
            + " SET expires_at = expires_at + make_interval(days => :days), reminded_at = NULL"
            + " WHERE id = :id AND user_id = :uid";

    /** 发放结果。 */
    public enum Outcome { GRANTED, OVER_CAP }

    /**
     * @param outcome   GRANTED / OVER_CAP
     * @param rentalIds 实际发放的名额 id（OVER_CAP 为空）
     * @param trigger   发放后应调用的 reconcile 触发（RENT → RENTAL_ADDED；RENEW → RENEWED）
     */
    public record Result(Outcome outcome, List<Long> rentalIds, PetSlotTrigger trigger) {
        public Result {
            rentalIds = List.copyOf(rentalIds);
        }

        public boolean granted() {
            return outcome == Outcome.GRANTED;
        }
    }

    private final NamedParameterJdbcTemplate jdbc;
    private final LongToIntFunction levelSlotsReader;
    private final PetSlotTokenGenerator tokens;

    @Autowired
    public PetSlotGranter(NamedParameterJdbcTemplate jdbc, PetSlotService slots, PlatformTransactionManager txManager,
            PetSlotTokenGenerator tokens) {
        this(jdbc, isolatedLevelReader(slots, txManager), tokens);
    }

    PetSlotGranter(NamedParameterJdbcTemplate jdbc, LongToIntFunction levelSlotsReader, PetSlotTokenGenerator tokens) {
        this.jdbc = jdbc;
        this.levelSlotsReader = levelSlotsReader;
        this.tokens = tokens;
    }

    /** 等级名额在独立只读事务里读：读失败只回滚这个小事务，不把到账事务标 rollback-only。 */
    private static LongToIntFunction isolatedLevelReader(PetSlotService slots, PlatformTransactionManager txManager) {
        TransactionTemplate tx = new TransactionTemplate(txManager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        tx.setReadOnly(true);
        return userId -> {
            Integer n = tx.execute(status -> slots.levelSlotsOf(userId));
            return n == null ? 0 : n;
        };
    }

    /** 发放（见类注释）；失败原样抛出，保存点已回滚。 */
    public Result grant(PetSlotPurchase row, Instant paidAt) {
        int levelSlots = levelSlotsReader.applyAsInt(row.getUserId());
        return GrantSavepoint.run(jdbc.getJdbcTemplate(), () -> grantInSavepoint(row, paidAt, levelSlots));
    }

    private Result grantInSavepoint(PetSlotPurchase row, Instant paidAt, int levelSlots) {
        long uid = row.getUserId();
        OffsetDateTime at = OffsetDateTime.ofInstant(paidAt, ZoneOffset.UTC);
        jdbc.query(LOCK_SQL, new MapSqlParameterSource("ns", PetSlotLock.PET_SLOT_LOCK_NS)
                .addValue("uid", PetSlotLock.lockKey(uid)), (RowCallbackHandler) rs -> { });
        Long liveRaw = jdbc.queryForObject(COUNT_LIVE_SQL,
                new MapSqlParameterSource("uid", uid).addValue("now", at), Long.class);
        long live = liveRaw == null ? 0 : liveRaw;

        boolean renew = row.getKind() == PetSlotPurchaseKind.RENEW;
        PetSlotTrigger trigger = renew ? PetSlotTrigger.RENEWED : PetSlotTrigger.RENTAL_ADDED;
        List<Long> selected = row.rentalIdList();
        Map<Long, Boolean> liveAtPaid = new HashMap<>();
        if (renew && !selected.isEmpty()) {
            jdbc.query(SELECT_SELECTED_SQL, new MapSqlParameterSource("uid", uid).addValue("ids", selected),
                    (RowCallbackHandler) rs -> {
                        OffsetDateTime exp = rs.getObject("expires_at", OffsetDateTime.class);
                        liveAtPaid.put(rs.getLong("id"),
                                "ACTIVE".equals(rs.getString("status")) && exp != null && exp.isAfter(at));
                    });
        }
        int toCreate = renew
                ? (int) selected.stream().filter(id -> !liveAtPaid.getOrDefault(id, false)).count()
                : row.getQuantity();
        if (toCreate > 0 && PetSlotService.DEFAULT_SLOTS + levelSlots + live + toCreate > PetSlotService.MAX_PETS) {
            return new Result(Outcome.OVER_CAP, List.of(), trigger);
        }

        List<Long> issued = new ArrayList<>();
        if (renew) {
            for (Long id : selected) {
                if (liveAtPaid.getOrDefault(id, false)) {
                    jdbc.update(EXTEND_SQL, new MapSqlParameterSource("days", row.getDays()).addValue("id", id)
                            .addValue("uid", uid));
                    issued.add(id);
                } else {
                    issued.add(insert(row, at));
                }
            }
        } else {
            for (int i = 0; i < row.getQuantity(); i++) {
                issued.add(insert(row, at));
            }
        }
        return new Result(Outcome.GRANTED, issued, trigger);
    }

    /** 名额来源：运营补发行 → ADMIN_GRANT（Story 4.3）；新租 / 续租改新建 → RENT（2.2 原口径）。 */
    static PetSlotRentalSource sourceOf(PetSlotPurchase row) {
        return row.getKind() == PetSlotPurchaseKind.ADMIN_GRANT ? PetSlotRentalSource.ADMIN_GRANT
                : PetSlotRentalSource.RENT;
    }

    private long insert(PetSlotPurchase row, OffsetDateTime startsAt) {
        Long id = jdbc.queryForObject(INSERT_SQL, new MapSqlParameterSource("token", tokens.generate())
                .addValue("uid", row.getUserId())
                .addValue("source", sourceOf(row).name())
                .addValue("startsAt", startsAt)
                .addValue("expiresAt", startsAt.plusDays(row.getDays()))
                .addValue("purchaseId", row.getId()), Long.class);
        if (id == null) {
            throw new IllegalStateException("pet_slot_rentals insert returned no id");
        }
        return id;
    }
}
