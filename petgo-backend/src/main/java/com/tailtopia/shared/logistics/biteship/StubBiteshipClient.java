package com.tailtopia.shared.logistics.biteship;

import com.tailtopia.shared.logistics.biteship.BiteshipException.Kind;
import com.tailtopia.shared.logistics.biteship.dto.BiteshipCreateOrderRequest;
import com.tailtopia.shared.logistics.biteship.dto.BiteshipOrderSnapshot;
import com.tailtopia.shared.logistics.biteship.dto.BiteshipRate;
import com.tailtopia.shared.logistics.biteship.dto.BiteshipRatesRequest;
import com.tailtopia.shared.logistics.biteship.dto.BiteshipRatesResult;
import com.tailtopia.shared.logistics.biteship.dto.CancelOutcome;
import com.tailtopia.shared.logistics.biteship.dto.CreateOutcome;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 确定性、不出网的 Biteship 桩（v1.4.2 shop Story 1.1）：L0 / L1 / 云端 / App 联调造数用。
 *
 * <ul>
 *   <li>默认两家：JNE Reguler 10,000（支持保价）、SiCepat REG 12,000（不支持保价）；</li>
 *   <li>目的邮编 {@code 99999} → 不可配送（{@code 40001010}）；{@code 99998} → 抛 SERVER 500；</li>
 *   <li>不校验 key、不看发货地邮编。</li>
 * </ul>
 * 运单三方法为内存实现（Story 5.1）：{@link BiteshipStubScript} 可推进状态 / 制造失败（仅桩模式有该 bean）。
 */
public class StubBiteshipClient implements BiteshipClient {

    public static final String NO_COURIER_POSTAL = "99999";
    public static final String FAILING_POSTAL = "99998";

    static final List<BiteshipRate> DEFAULT_RATES = List.of(
            new BiteshipRate("jne", "JNE", "reg", "Reguler", "2 - 3 days", "2 - 3", "days",
                    10_000L, true),
            new BiteshipRate("sicepat", "SiCepat", "reg", "REG", "1 - 2 days", "1 - 2", "days",
                    12_000L, false));

    private static final Set<String> CANCELLABLE =
            Set.of("confirmed", "scheduled", "allocated", "picking_up");

    private final Map<String, BiteshipOrderSnapshot> orders = new ConcurrentHashMap<>();
    private final AtomicLong waybillSeq = new AtomicLong(1);

    // ---------- 测试钩子状态（仅经 BiteshipStubScript 操作） ----------
    private final AtomicReference<String> failNextCreateCode = new AtomicReference<>();
    private final AtomicBoolean timeoutNextCreate = new AtomicBoolean();
    private final AtomicBoolean failNextGetOrder = new AtomicBoolean();

    @Override
    public String providerMode() {
        return "STUB";
    }

    @Override
    public BiteshipRatesResult rates(BiteshipRatesRequest req) {
        if (NO_COURIER_POSTAL.equals(req.destPostalCode())) {
            return BiteshipRatesResult.noCourier(40001010);
        }
        if (FAILING_POSTAL.equals(req.destPostalCode())) {
            throw new BiteshipException(Kind.SERVER, 500, null);
        }
        return BiteshipRatesResult.of(DEFAULT_RATES);
    }

    /**
     * 桩下单：{@code id = "stub_" + referenceId}、{@code waybill_id = "STUBWB" + 10 位数字}、{@code status = confirmed}、
     * {@code price} = 请求里的运费。同一 referenceId 重复 → {@code Rejected("40002060")}（同 Biteship）。
     */
    @Override
    public CreateOutcome createOrder(BiteshipCreateOrderRequest req) {
        String code = failNextCreateCode.getAndSet(null);
        if (code != null) {
            return new CreateOutcome.Rejected(code, "stub rejected");
        }
        if (timeoutNextCreate.getAndSet(false)) {
            return new CreateOutcome.Unknown("NETWORK");
        }
        String id = "stub_" + req.referenceId();
        if (orders.containsKey(id)) {
            return new CreateOutcome.Rejected("40002060", "reference_id already used");
        }
        BiteshipOrderSnapshot snap = new BiteshipOrderSnapshot(id, "confirmed",
                "STUBWB" + String.format("%010d", waybillSeq.getAndIncrement()), null,
                req.courierCompany(), req.courierType(), req.price(), null, Instant.now());
        orders.put(id, snap);
        return new CreateOutcome.Created(snap);
    }

    @Override
    public CancelOutcome cancelOrder(String biteshipOrderId, String reason) {
        BiteshipOrderSnapshot snap = orders.get(biteshipOrderId);
        if (snap == null) {
            return new CancelOutcome.Unknown("UNKNOWN_ORDER");
        }
        if (!CANCELLABLE.contains(snap.status())) {
            return new CancelOutcome.NotCancellable(snap.status());
        }
        orders.put(biteshipOrderId, withStatus(snap, "cancelled"));
        return new CancelOutcome.Cancelled();
    }

    @Override
    public BiteshipOrderSnapshot getOrder(String biteshipOrderId) {
        if (failNextGetOrder.getAndSet(false)) {
            throw new BiteshipException(Kind.NETWORK, null, null);
        }
        BiteshipOrderSnapshot snap = orders.get(biteshipOrderId);
        if (snap == null) {
            throw new BiteshipException(Kind.BAD_REQUEST, 404, null);
        }
        return snap;
    }

    // ---------- 测试钩子（BiteshipStubScript 委托） ----------

    void scriptSetStatus(String biteshipOrderId, String status) {
        BiteshipOrderSnapshot snap = orders.get(biteshipOrderId);
        if (snap == null) {
            throw new IllegalArgumentException("unknown stub order");
        }
        orders.put(biteshipOrderId, withStatus(snap, status));
    }

    void scriptFailNextCreate(String code) {
        failNextCreateCode.set(code == null ? "40000001" : code);
    }

    void scriptTimeoutNextCreate() {
        timeoutNextCreate.set(true);
    }

    void scriptFailNextGetOrder() {
        failNextGetOrder.set(true);
    }

    private static BiteshipOrderSnapshot withStatus(BiteshipOrderSnapshot s, String status) {
        return new BiteshipOrderSnapshot(s.id(), status, s.waybillId(), s.trackingId(), s.courierCompany(),
                s.courierType(), s.price(), s.link(), Instant.now());
    }
}
