package com.tailtopia.merchant.binding.service;

import com.tailtopia.merchant.binding.domain.UserMerchantBinding;
import com.tailtopia.merchant.binding.repository.UserMerchantBindingRepository;
import com.tailtopia.merchant.store.MerchantStoreDirectory;
import com.tailtopia.merchant.store.MerchantStoreDirectory.MerchantStoreRef;
import com.tailtopia.shared.error.AppException;
import com.tailtopia.shared.error.ErrorTypes;
import com.tailtopia.shared.ratelimit.RedisRateLimiter;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * App 用户填门店邀请码（契约第 1 节 TailTopia 侧验收，FR-7 / FR-40）。
 *
 * <ul>
 *   <li>比较前去掉空格（码为 6 位纯数字，无需转大写）；</li>
 *   <li>只有 {@code ACTIVE} 门店的码可以新绑定，停用门店的码与不存在的码<b>同样提示无效</b>；</li>
 *   <li>已有绑定不受门店停用影响（本类从不回头校验已有绑定的门店状态）；</li>
 *   <li>绑定过任何门店后不能再填码。</li>
 * </ul>
 */
@Service
public class MerchantBindingService {

    /** 码是 6 位顺序数字，可枚举：限流挡住拿脚本逐个试码（试中也只会绑到一家随机门店，但没必要放任）。 */
    private static final int BIND_ATTEMPTS_PER_HOUR = 10;

    private final UserMerchantBindingRepository bindings;
    private final MerchantStoreDirectory stores;
    private final RedisRateLimiter rateLimiter;

    public MerchantBindingService(UserMerchantBindingRepository bindings, MerchantStoreDirectory stores,
            RedisRateLimiter rateLimiter) {
        this.bindings = bindings;
        this.stores = stores;
        this.rateLimiter = rateLimiter;
    }

    /** 当前绑定（未绑定为 empty）。 */
    @Transactional(readOnly = true)
    public Optional<BindingView> current(long userId) {
        return bindings.findById(userId).map(this::view);
    }

    /** 下单归属门店：用户此刻绑定的门店 id，不看门店状态（AD-19）。 */
    @Transactional(readOnly = true)
    public Long referrerOf(long userId) {
        return bindings.findById(userId).map(UserMerchantBinding::getMerchantStoreId).orElse(null);
    }

    @Transactional
    public BindingView bind(long userId, String rawCode) {
        rateLimiter.check("rl:merchant:bind:" + userId, BIND_ATTEMPTS_PER_HOUR, Duration.ofHours(1));
        if (bindings.existsById(userId)) {
            throw alreadyBound();
        }
        String code = normalize(rawCode);
        MerchantStoreRef store = code.isEmpty() ? null : stores.findByInviteCode(code).orElse(null);
        if (store == null || !store.isActive()) {
            throw new AppException(HttpStatus.UNPROCESSABLE_ENTITY, ErrorTypes.MERCHANT_CODE_INVALID,
                    "邀请码无效");
        }
        try {
            UserMerchantBinding saved = bindings.saveAndFlush(
                    UserMerchantBinding.of(userId, store.id(), Instant.now()));
            return new BindingView(store.inviteCode(), saved.getBoundAt());
        } catch (DataIntegrityViolationException e) {
            // 同一用户两次并发填码：主键 user_id 只让一个落库，后到者按「已绑定」回。
            throw alreadyBound();
        }
    }

    /** 去掉全部空白（含中间的空格，用户常照着「000 035」这样分组抄码）。 */
    static String normalize(String raw) {
        return raw == null ? "" : raw.replaceAll("\\s+", "");
    }

    private BindingView view(UserMerchantBinding b) {
        String code = stores.findById(b.getMerchantStoreId()).map(MerchantStoreRef::inviteCode).orElse(null);
        return new BindingView(code, b.getBoundAt());
    }

    private static AppException alreadyBound() {
        return new AppException(HttpStatus.CONFLICT, ErrorTypes.MERCHANT_ALREADY_BOUND, "已绑定门店，不能再填邀请码");
    }

    /** @param inviteCode 绑定门店的邀请码（门店记录已不存在时为 null） */
    public record BindingView(String inviteCode, Instant boundAt) {
    }
}
