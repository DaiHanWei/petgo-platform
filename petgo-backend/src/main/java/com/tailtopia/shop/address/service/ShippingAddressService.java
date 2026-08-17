package com.tailtopia.shop.address.service;

import com.tailtopia.shared.error.AppException;
import com.tailtopia.shop.address.domain.AddressFields;
import com.tailtopia.shop.address.domain.IndonesiaPhone;
import com.tailtopia.shop.address.domain.ShippingAddress;
import com.tailtopia.shop.address.repository.ShippingAddressRepository;
import com.tailtopia.shop.service.ShopTokenGenerator;
import java.util.Comparator;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 地址簿（Story 2.1，FR-98）。
 *
 * <p>🔒 <b>本类处理三项 PII</b>（收件人姓名 / 履约手机号 / 详细地址）——
 * <b>不打任何含这些字段的日志</b>，异常 detail 也不回显用户输入（NFR-5）。
 *
 * <p>🔴 <b>越权一律 404 不 403</b>：403 等于告诉攻击者「这个 token 存在」，
 * 配合可枚举的猜测就能探出他人地址是否存在。查询直接按 (token, userId) 双条件走，
 * 让「不是你的」和「不存在」在代码路径上就是同一件事——而不是查出来再判断。
 */
@Service
public class ShippingAddressService {

    private final ShippingAddressRepository addresses;
    private final ShopTokenGenerator tokens;
    private final int maxPerUser;

    public ShippingAddressService(ShippingAddressRepository addresses, ShopTokenGenerator tokens,
            @Value("${petgo.shop.address-max-per-user:20}") int maxPerUser) {
        this.addresses = addresses;
        this.tokens = tokens;
        this.maxPerUser = maxPerUser;
    }

    @Transactional(readOnly = true)
    public List<ShippingAddress> list(long userId) {
        return addresses.findByUserIdOrderByIsDefaultDescIdDesc(userId);
    }

    /** 🔴 越权与不存在走同一条路径 → 同一个 404。 */
    @Transactional(readOnly = true)
    public ShippingAddress require(long userId, String token) {
        return addresses.findByPublicTokenAndUserId(token, userId)
                .orElseThrow(() -> AppException.notFound("地址不存在"));
    }

    /**
     * 新建。
     *
     * <p>🔴 上限 {@code 20}，第 21 条<b>返回明确的领域错误而非静默失败</b>——
     * 静默失败会让用户以为存上了，直到结算时找不到地址。
     * <p>🔴 <b>首个地址自动设为默认</b>：没有默认地址的地址簿在结算页是个死胡同。
     */
    @Transactional
    public ShippingAddress create(long userId, AddressFields raw) {
        long count = addresses.countByUserId(userId);
        if (count >= maxPerUser) {
            throw AppException.validation(
                    "地址簿最多保存 %d 条，请先删除不用的地址".formatted(maxPerUser));
        }
        AddressFields f = normalized(raw);
        ShippingAddress a = ShippingAddress.create(userId, tokens.generate(), f);
        if (count == 0) {
            a.markDefault(true);
        }
        return addresses.save(a);
    }

    @Transactional
    public ShippingAddress update(long userId, String token, AddressFields raw) {
        ShippingAddress a = require(userId, token);
        a.apply(normalized(raw));
        return addresses.save(a);
    }

    /**
     * 删除。
     *
     * <p>🔴 删掉默认地址后，<b>剩余中「最近使用」的一条自动升为默认</b>；
     * 地址簿清空则无默认。不这么做会留下一个「有地址但没有默认」的状态，
     * 结算页要么报错要么随便挑一个——后者是发错货的经典成因。
     */
    @Transactional
    public void delete(long userId, String token) {
        ShippingAddress a = require(userId, token);
        boolean wasDefault = a.isDefault();
        addresses.delete(a);
        addresses.flush();          // 先落删除，避免部分唯一索引与下面的置默认打架
        if (wasDefault) {
            promoteNextDefault(userId);
        }
    }

    /** 显式设为默认。 */
    @Transactional
    public ShippingAddress setDefault(long userId, String token) {
        ShippingAddress target = require(userId, token);
        if (target.isDefault()) {
            return target;
        }
        // 🔴 先清后设，且中间 flush：DB 上有 (user_id) WHERE is_default 的部分唯一索引，
        //    不 flush 会在同一次刷写里同时存在两个 default 而撞唯一约束。
        addresses.findByUserIdAndIsDefaultTrue(userId).ifPresent(old -> {
            old.markDefault(false);
            addresses.saveAndFlush(old);
        });
        target.markDefault(true);
        return addresses.save(target);
    }

    /** 结算选用时打点，供删除后的默认升级排序。 */
    @Transactional
    public void markUsed(long userId, String token) {
        require(userId, token).touchUsed();
    }

    public int maxPerUser() {
        return maxPerUser;
    }

    // ---------- 内部 ----------

    private void promoteNextDefault(long userId) {
        List<ShippingAddress> rest = addresses.findByUserIdOrderByIsDefaultDescIdDesc(userId);
        if (rest.isEmpty()) {
            return;     // 地址簿清空 → 无默认，合法状态
        }
        // 最近使用优先；从未用过的排在后面，同档按更新时间倒序
        rest.sort(Comparator
                .comparing((ShippingAddress x) -> x.getLastUsedAt(),
                        Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(ShippingAddress::getUpdatedAt, Comparator.reverseOrder()));
        rest.getFirst().markDefault(true);
        addresses.save(rest.getFirst());
    }

    /** 🔴 手机号归一化是写入前的强制步骤，不能留给调用方"记得调"。 */
    private static AddressFields normalized(AddressFields f) {
        return new AddressFields(
                requireText(f.receiverName(), "请填写收件人姓名", 40),
                IndonesiaPhone.normalize(f.receiverPhone()),
                requireText(f.provinsi(), "请选择省份", 60),
                requireText(f.kotaKabupaten(), "请选择城市/县", 60),
                requireText(f.kecamatan(), "请选择 Kecamatan", 60),
                requireText(f.addressLine(), "请填写详细地址", 120),
                requireKodePos(f.kodePos()),
                trimToNull(f.label()));
    }

    /** 🔒 报错只说哪个字段不合规，绝不回显用户输入（可能是 PII）。 */
    private static String requireText(String v, String message, int max) {
        String s = v == null ? "" : v.trim();
        if (s.isEmpty()) {
            throw AppException.validation(message);
        }
        if (s.length() > max) {
            throw AppException.validation(message + "（最多 %d 字）".formatted(max));
        }
        return s;
    }

    private static String requireKodePos(String v) {
        String s = v == null ? "" : v.trim();
        if (!s.matches("^[0-9]{5}$")) {
            throw AppException.validation("邮编应为 5 位数字");
        }
        return s;
    }

    private static String trimToNull(String v) {
        if (v == null) {
            return null;
        }
        String s = v.trim();
        if (s.isEmpty()) {
            return null;
        }
        return s.length() > 10 ? s.substring(0, 10) : s;
    }
}
