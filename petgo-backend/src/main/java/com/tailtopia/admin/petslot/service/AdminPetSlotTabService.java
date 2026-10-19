package com.tailtopia.admin.petslot.service;

import com.tailtopia.account.repository.AccountDeletionRepository;
import com.tailtopia.admin.account.domain.AdminAccount;
import com.tailtopia.admin.account.repository.AdminAccountRepository;
import com.tailtopia.admin.shared.time.AdminTime;
import com.tailtopia.auth.domain.User;
import com.tailtopia.auth.service.AccountQueryService;
import com.tailtopia.pay.domain.PaymentIntent;
import com.tailtopia.pay.repository.PaymentIntentRepository;
import com.tailtopia.profile.domain.PetProfile;
import com.tailtopia.profile.service.PetResolver;
import com.tailtopia.profile.slot.PetSlotQuota;
import com.tailtopia.profile.slot.PetSlotService;
import com.tailtopia.profile.slot.domain.PetSlotPurchase;
import com.tailtopia.profile.slot.domain.PetSlotPurchaseKind;
import com.tailtopia.profile.slot.domain.PetSlotPurchaseStatus;
import com.tailtopia.profile.slot.domain.PetSlotRental;
import com.tailtopia.profile.slot.repository.PetSlotPurchaseRepository;
import com.tailtopia.profile.slot.repository.PetSlotRentalRepository;
import com.tailtopia.shared.error.AppException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 用户抽屉「宠物与名额」页签读模型（V1.4.0 multi-pet Story 4.3 · AD-15 · AB-34.1，UI 稿 L1 / L6）。<b>纯只读</b>。
 *
 * <p>五块自上而下：名额构成四格 → 宠物状态表 → 月租名额表 → 购买记录表 → 补发卡（模板按 {@link Tab#full} / 权限渲染）。
 * <ul>
 *   <li>🔴 名额构成只取 {@link PetSlotService#quotaOf}（单一入口，架构 §5），本类不自算等级名额；</li>
 *   <li>「月租（生效中）」取<b>实际持有行数</b>（{@code ACTIVE AND expires_at > now}，含运营补发）—— {@code quotaOf.rentalSlots}
 *       是封顶后的有效值，超 5 时二者不同，后台要看真实持有数（AC3.2）；</li>
 *   <li>宠物只经 {@link PetResolver#listOwned}（{@code created_at ASC, id ASC}）；</li>
 *   <li>购买记录：已结算的 PAID / ORPHAN_PAID / OVERPAID（后两者是「已收款未发放」的异常单，单独标出交运营处理，
 *       2.2 待确认），{@code created_at DESC, id DESC} 最多 20 条；</li>
 *   <li>🔴 禁 N+1：操作人显示名、payment_intent token 各一次 {@code findAllById}；</li>
 *   <li>对外标识不可枚举：不出现 rental / purchase / payment_intent 的数字主键（intent 只给 {@code public_token}）。</li>
 * </ul>
 */
@Service
public class AdminPetSlotTabService {

    /** 购买记录最多显示条数（AC3.2，不翻页）。 */
    public static final int PURCHASE_LIMIT = 20;

    /** 购买记录列出的状态：已结算（钱已到）的三态。PENDING / FAILED / EXPIRED 不在此显示（支付详情在「支付单」页）。 */
    static final Set<PetSlotPurchaseStatus> LISTED = EnumSet.of(PetSlotPurchaseStatus.PAID,
            PetSlotPurchaseStatus.ORPHAN_PAID, PetSlotPurchaseStatus.OVERPAID);

    private final AccountQueryService accounts;
    private final PetSlotService slots;
    private final PetResolver petResolver;
    private final PetSlotRentalRepository rentals;
    private final PetSlotPurchaseRepository purchases;
    private final AdminAccountRepository adminAccounts;
    private final PaymentIntentRepository intents;
    private final AdminTime time;
    private final AccountDeletionRepository deletions;

    public AdminPetSlotTabService(AccountQueryService accounts, PetSlotService slots, PetResolver petResolver,
            PetSlotRentalRepository rentals, PetSlotPurchaseRepository purchases,
            AdminAccountRepository adminAccounts, PaymentIntentRepository intents, AdminTime time,
            AccountDeletionRepository deletions) {
        this.deletions = deletions;
        this.accounts = accounts;
        this.slots = slots;
        this.petResolver = petResolver;
        this.rentals = rentals;
        this.purchases = purchases;
        this.adminAccounts = adminAccounts;
        this.intents = intents;
        this.time = time;
    }

    /**
     * 页签视图。
     *
     * @param userId          目标用户（补发表单 action 用；用户 id 是后台既有展示口径）
     * @param deleted         已注销（页签只显示「账号已注销，名额数据已清理」，不查任何名额数据、无补发卡）
     * @param deletionPending 注销已受理、级联未完成（{@code account_deletions} 有行而 {@code deletedAt} 未落）：数据照常展示，
     *                        不渲染补发卡（服务端同时 422，code review #2）
     * @param full            名额总数已到 5（补发卡置灰 + 提示，服务端同时 422）
     * @param rentedActive    生效中月租实际行数（= 月租名额表标题里的数）
     * @param purchasesTruncated 购买记录超过 {@value #PURCHASE_LIMIT} 条（显示「仅显示最近 20 条」）
     * @param hasPurchaseExceptions 购买记录里有 ORPHAN_PAID / OVERPAID（显示处理提示）
     */
    public record Tab(long userId, boolean deleted, boolean deletionPending, boolean full, int quotaTotal, int defaultSlots, int levelSlots,
            int rentedActive, int petCount, List<PetRow> pets, List<RentalRow> rentals,
            List<PurchaseRow> purchases, boolean purchasesTruncated, boolean hasPurchaseExceptions) {

        public Tab {
            pets = List.copyOf(pets);
            rentals = List.copyOf(rentals);
            purchases = List.copyOf(purchases);
        }

        static Tab deletedAccount(long userId) {
            return new Tab(userId, true, false, false, 0, 0, 0, 0, 0, List.of(), List.of(), List.of(), false, false);
        }
    }

    /** 宠物状态表一行。{@code status} = {@code ACTIVE} / {@code READ_ONLY}（模板取文案键）。档案 ID 沿用 L0 后台既有口径。 */
    public record PetRow(long id, String name, String createdAtWibDate, String status) {
    }

    /** 月租名额表一行。{@code seq} 为本表内 1..n 显示序，不外露名额主键 / token。 */
    public record RentalRow(int seq, String startsAtWib, String expiresAtWib, boolean active) {
    }

    /**
     * 购买记录一行。
     *
     * @param kind             {@code RENT} / {@code RENEW} / {@code ADMIN_GRANT}
     * @param status           {@code PAID} / {@code ORPHAN_PAID} / {@code OVERPAID}
     * @param operatorName     ADMIN_GRANT 的操作人显示名（查不到 → {@code #id}）；其余 null
     * @param totalOrNull      金额（IDR）；ADMIN_GRANT 为 null（显示「—」）
     * @param channelOrNull    {@code PAWCOIN} / {@code QRIS}；ADMIN_GRANT 为 null
     * @param intentTokenOrNull payment_intent 的 {@code public_token}（只 QRIS 有）
     * @param intentTokenShort  缩写「前 6 + … + 后 3」
     */
    public record PurchaseRow(String createdAtWib, String kind, String status, String operatorName, int days,
            int quantity, Long totalOrNull, String channelOrNull, String intentTokenOrNull,
            String intentTokenShort) {

        /** 已收款但未发放名额（ORPHAN_PAID / OVERPAID）：模板标红、提示人工补发或退款。 */
        public boolean exception() {
            return !PetSlotPurchaseStatus.PAID.name().equals(status);
        }
    }

    @Transactional(readOnly = true)
    public Tab tab(long userId) {
        User u = accounts.findUserById(userId)
                .orElseThrow(() -> AppException.notFound("用户不存在").code("admin.err.user.notFound"));
        if (u.getDeletedAt() != null) {
            return Tab.deletedAccount(userId);
        }
        PetSlotQuota quota = slots.quotaOf(userId);
        Instant now = slots.now();

        List<PetRow> pets = new ArrayList<>();
        for (PetProfile p : petResolver.listOwned(userId)) {
            pets.add(new PetRow(p.getId(), p.getName(), time.fmtDate(p.getCreatedAt()),
                    p.getStatus() == null ? "ACTIVE" : p.getStatus().name()));
        }

        List<PetSlotRental> all = rentals.findByUserIdOrderByExpiresAtAscIdAsc(userId);
        List<PetSlotRental> live = new ArrayList<>();
        List<PetSlotRental> ended = new ArrayList<>();
        for (PetSlotRental r : all) {
            (r.isLiveAt(now) ? live : ended).add(r);
        }
        live.sort(Comparator.comparing(PetSlotRental::getExpiresAt).thenComparing(PetSlotRental::getId,
                Comparator.nullsLast(Comparator.naturalOrder())));
        ended.sort(Comparator.comparing(PetSlotRental::getExpiresAt).reversed().thenComparing(PetSlotRental::getId,
                Comparator.nullsLast(Comparator.reverseOrder())));
        List<RentalRow> rentalRows = new ArrayList<>();
        int seq = 0;
        for (PetSlotRental r : live) {
            rentalRows.add(new RentalRow(++seq, time.fmt(r.getStartsAt()), time.fmt(r.getExpiresAt()), true));
        }
        for (PetSlotRental r : ended) {
            rentalRows.add(new RentalRow(++seq, time.fmt(r.getStartsAt()), time.fmt(r.getExpiresAt()), false));
        }

        List<PetSlotPurchase> page = purchases.findByUserIdAndStatusInOrderByCreatedAtDescIdDesc(userId, LISTED,
                PageRequest.of(0, PURCHASE_LIMIT + 1));
        boolean truncated = page.size() > PURCHASE_LIMIT;
        List<PetSlotPurchase> shown = truncated ? page.subList(0, PURCHASE_LIMIT) : page;
        Map<Long, String> operatorNames = operatorNames(shown);
        Map<Long, String> intentTokens = intentTokens(shown);
        List<PurchaseRow> purchaseRows = new ArrayList<>();
        boolean exceptions = false;
        for (PetSlotPurchase p : shown) {
            boolean grant = p.getKind() == PetSlotPurchaseKind.ADMIN_GRANT;
            String token = p.getPaymentIntentId() == null ? null : intentTokens.get(p.getPaymentIntentId());
            String operator = grant && p.getOperatorAdminId() != null
                    ? operatorNames.getOrDefault(p.getOperatorAdminId(), "#" + p.getOperatorAdminId())
                    : null;
            PurchaseRow row = new PurchaseRow(time.fmt(p.getCreatedAt()), p.getKind().name(), p.getStatus().name(),
                    operator, p.getDays(), p.getQuantity(), grant ? null : p.getTotal(),
                    grant || p.getChannel() == null ? null : p.getChannel().name(), token, shortToken(token));
            exceptions |= row.exception();
            purchaseRows.add(row);
        }

        boolean deletionPending = deletions.findByUserId(userId).isPresent();
        return new Tab(userId, false, deletionPending, quota.total() >= PetSlotService.MAX_PETS, quota.total(),
                quota.defaultSlots(), quota.levelSlots(), live.size(), quota.petCount(), pets, rentalRows,
                purchaseRows, truncated, exceptions);
    }

    /** 操作人显示名：一次批量查询（空集不查）。 */
    private Map<Long, String> operatorNames(List<PetSlotPurchase> rows) {
        Set<Long> ids = new LinkedHashSet<>();
        for (PetSlotPurchase p : rows) {
            if (p.getKind() == PetSlotPurchaseKind.ADMIN_GRANT && p.getOperatorAdminId() != null) {
                ids.add(p.getOperatorAdminId());
            }
        }
        Map<Long, String> out = new HashMap<>();
        if (!ids.isEmpty()) {
            for (AdminAccount a : adminAccounts.findAllById(ids)) {
                out.put(a.getId(), a.getDisplayName());
            }
        }
        return out;
    }

    /** payment_intent 对外 token：一次批量查询（空集不查）。 */
    private Map<Long, String> intentTokens(List<PetSlotPurchase> rows) {
        Set<Long> ids = new LinkedHashSet<>();
        rows.stream().map(PetSlotPurchase::getPaymentIntentId).filter(Objects::nonNull).forEach(ids::add);
        Map<Long, String> out = new HashMap<>();
        if (!ids.isEmpty()) {
            for (PaymentIntent pi : intents.findAllById(ids)) {
                out.put(pi.getId(), pi.getPublicToken());
            }
        }
        return out;
    }

    /** token 缩写：前 6 + {@code …} + 后 3（UI 稿「pi_8f2…a91」）；≤ 9 位原样；null → null。 */
    static String shortToken(String token) {
        if (token == null) {
            return null;
        }
        return token.length() <= 9 ? token : token.substring(0, 6) + "…" + token.substring(token.length() - 3);
    }
}
