package com.tailtopia.shop.returns.web;

import com.tailtopia.pay.refund.domain.PayoutChannel;
import com.tailtopia.shared.error.AppException;
import com.tailtopia.shop.returns.domain.CashDestination;
import com.tailtopia.shop.returns.domain.ReturnRequest;
import com.tailtopia.shop.returns.domain.ReturnType;
import com.tailtopia.shop.returns.dto.ReturnEligibilityView;
import com.tailtopia.shop.returns.dto.ReturnProgressView;
import com.tailtopia.shop.returns.repository.ReturnRequestRepository;
import com.tailtopia.shop.returns.service.ReturnQueryService;
import com.tailtopia.shop.returns.service.ReturnRequestService;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 用户侧退货（Story 5.7 申请页 · 5.8 退款方式页 · 5.9 进度页的后端）。
 *
 * <p>🔒 全部在 {@code /me} 下，越权与不存在同为 404（与订单同口径）。
 *
 * <p>🔴 <b>本控制器不含任何金额计算与可退判定</b>：两者都在 service，前端也只认这里下发的
 * 结论与文案依据。规则散在两侧的后果不是「不一致」，而是<b>只在某些行上不一致</b>。
 */
@RestController
@RequestMapping("/api/v1/me")
public class MeReturnController {

    private final ReturnRequestService requests;
    private final ReturnQueryService queries;
    private final ReturnRequestRepository returns;

    public MeReturnController(ReturnRequestService requests, ReturnQueryService queries,
            ReturnRequestRepository returns) {
        this.requests = requests;
        this.queries = queries;
        this.returns = returns;
    }

    /**
     * 退货申请页数据（Story 5.7）。
     *
     * <p>🔴 不可退的行<b>照样下发</b>，带 {@code selectable=false} 与原因 ——
     * 前端据此置灰并标注，而不是把它们藏起来（藏起来用户会以为商品丢了）。
     */
    @GetMapping("/shop-orders/{token}/return-eligibility")
    public ReturnEligibilityView eligibility(@AuthenticationPrincipal Jwt jwt,
            @PathVariable String token) {
        return queries.eligibility(currentUserId(jwt), token);
    }

    /** 提交退货申请。🔴 行级勾选（FR-104A）；同订单进行中至多一张 → 409（C-12）。 */
    @PostMapping("/shop-returns")
    @ResponseStatus(HttpStatus.CREATED)
    public ReturnProgressView submit(@AuthenticationPrincipal Jwt jwt,
            @RequestBody(required = false) SubmitReturnRequest req) {
        if (req == null || req.orderToken() == null || req.orderToken().isBlank()) {
            throw AppException.validation("缺少订单号");
        }
        long userId = currentUserId(jwt);
        ReturnRequest r = requests.submit(userId, req.orderToken(),
                ReturnType.parse(req.returnType()), req.selections(), req.reasonNote(),
                req.evidenceKeys());
        return queries.progress(userId, r.getPublicToken());
    }

    @GetMapping("/shop-returns")
    public List<ReturnProgressView> myReturns(@AuthenticationPrincipal Jwt jwt) {
        return queries.myReturns(currentUserId(jwt));
    }

    /** 退货进度（Story 5.9）/ 退款方式页（Story 5.8）共用同一份数据。 */
    @GetMapping("/shop-returns/{token}")
    public ReturnProgressView progress(@AuthenticationPrincipal Jwt jwt,
            @PathVariable String token) {
        return queries.progress(currentUserId(jwt), token);
    }

    /**
     * 选择<b>现金段</b>的去向（Story 5.8）。
     *
     * <p>🔴 <b>没有「PawCoin 段去哪」这个参数</b> —— 不是校验后拒绝，是接口里根本没有它
     * （FR-100A 规则 1，能力缺席）。前端也因此无从渲染那个选项。
     *
     * <p>🔴 渠道费由后端按 {@link PayoutChannel} 权威计算，<b>前端传来的费一律不采信</b>。
     */
    @PostMapping("/shop-returns/{token}/cash-destination")
    @Transactional
    public ReturnProgressView chooseCashDestination(@AuthenticationPrincipal Jwt jwt,
            @PathVariable String token, @RequestBody(required = false) CashDestinationRequest req) {
        long userId = currentUserId(jwt);
        ReturnRequest r = returns.findByPublicTokenAndUserId(token, userId)
                .orElseThrow(() -> AppException.notFound("退货申请不存在"));
        if (req == null) {
            throw AppException.validation("请选择退款方式");
        }
        r.chooseCashDestination(parseDestination(req.cashDestination()),
                parseChannel(req.payoutChannel()), req.payoutAccount(), req.accountHolderName());
        returns.save(r);
        return queries.progress(userId, token);
    }

    /** S-7：用户上传寄回运单凭证（平台承担运费的情形据此返还）。 */
    @PostMapping("/shop-returns/{token}/shipback")
    @Transactional
    public ReturnProgressView registerShipback(@AuthenticationPrincipal Jwt jwt,
            @PathVariable String token, @RequestBody(required = false) ShipbackRequest req) {
        long userId = currentUserId(jwt);
        ReturnRequest r = returns.findByPublicTokenAndUserId(token, userId)
                .orElseThrow(() -> AppException.notFound("退货申请不存在"));
        if (req == null) {
            throw AppException.validation("请填写寄回运单信息");
        }
        r.registerShipback(req.carrier(), req.trackingNo(), req.fee());
        returns.save(r);
        return queries.progress(userId, token);
    }

    /** S-8 ④：用户主动撤销（待审核 / 待寄回两态）。 */
    @PostMapping("/shop-returns/{token}/withdraw")
    public ReturnProgressView withdraw(@AuthenticationPrincipal Jwt jwt,
            @PathVariable String token) {
        long userId = currentUserId(jwt);
        requests.withdraw(userId, token);
        return queries.progress(userId, token);
    }

    // ---------- 请求体 ----------

    /**
     * @param selections 订单行 id → 退货数量。🔴 行级，不是整单
     */
    public record SubmitReturnRequest(String orderToken, String returnType,
            Map<Long, Integer> selections, String reasonNote, List<String> evidenceKeys) {
    }

    /** 🔴 只有现金段的去向 —— PawCoin 段没有可选项，见方法注释。 */
    public record CashDestinationRequest(String cashDestination, String payoutChannel,
            String payoutAccount, String accountHolderName) {
    }

    public record ShipbackRequest(String carrier, String trackingNo, Long fee) {
    }

    // ---------- 内部 ----------

    private static CashDestination parseDestination(String raw) {
        if (raw != null) {
            for (CashDestination d : CashDestination.values()) {
                if (d.name().equalsIgnoreCase(raw.trim())) {
                    return d;
                }
            }
        }
        throw AppException.validation("请选择退款方式");
    }

    /** 转 PawCoin 时无渠道，允许为空。 */
    private static PayoutChannel parseChannel(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        for (PayoutChannel c : PayoutChannel.values()) {
            if (c.name().equalsIgnoreCase(raw.trim())) {
                return c;
            }
        }
        throw AppException.validation("收款渠道不支持");
    }

    private static long currentUserId(Jwt jwt) {
        if (jwt == null || jwt.getSubject() == null) {
            throw AppException.unauthorized("需要登录后访问");
        }
        try {
            return Long.parseLong(jwt.getSubject());
        } catch (NumberFormatException e) {
            throw AppException.unauthorized("无效的登录凭证");
        }
    }
}
