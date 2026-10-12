package com.tailtopia.ops.popup.service;

import com.tailtopia.auth.service.AccountQueryService;
import com.tailtopia.ops.popup.domain.OpsPopup;
import com.tailtopia.ops.popup.domain.OpsPopupImpression;
import com.tailtopia.ops.popup.repository.OpsPopupImpressionRepository;
import com.tailtopia.ops.popup.repository.OpsPopupRepository;
import com.tailtopia.shared.error.AppException;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 运营弹窗曝光上报（V1.3.4 Story 5.1 · AC4）。App 在弹窗<b>真正显示后</b>才报（AD-14）。
 *
 * <p>token 存在即记（无论当前状态 / 时间窗：弹窗可能在显示瞬间被停用，曝光依然发生过）；
 * 不去重；{@code shown_at} 取服务端时刻。
 *
 * <p>写入前确认调用者是存在且未注销的普通用户（{@link AccountQueryService#findUserById} 只认 role=USER），
 * 否则 404 —— 已注销账号的残留 token 不得再往曝光表写行（注销刚清过它，D1/D2）。
 */
@Service
public class OpsPopupImpressionService {

    private final OpsPopupRepository popups;
    private final OpsPopupImpressionRepository impressions;
    private final AccountQueryService accounts;
    private final Clock clock;

    @Autowired
    public OpsPopupImpressionService(OpsPopupRepository popups, OpsPopupImpressionRepository impressions,
            AccountQueryService accounts) {
        this(popups, impressions, accounts, Clock.systemUTC());
    }

    OpsPopupImpressionService(OpsPopupRepository popups, OpsPopupImpressionRepository impressions,
            AccountQueryService accounts, Clock clock) {
        this.popups = popups;
        this.impressions = impressions;
        this.accounts = accounts;
        this.clock = clock;
    }

    @Transactional
    public void record(String token, long userId) {
        if (accounts.findUserById(userId).filter(u -> u.getDeletedAt() == null).isEmpty()) {
            throw AppException.notFound("用户不存在");
        }
        OpsPopup popup = popups.findByPublicToken(token)
                .orElseThrow(() -> AppException.notFound("弹窗不存在"));
        impressions.save(OpsPopupImpression.of(popup.getId(), userId, clock.instant()));
    }
}
