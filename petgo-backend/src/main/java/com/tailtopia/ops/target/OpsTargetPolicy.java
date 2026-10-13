package com.tailtopia.ops.target;

import com.tailtopia.shop.domain.ProductCategory;
import com.tailtopia.shop.repository.ShopProductRepository;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * 统一跳转目标的<b>唯一</b>校验口径（V1.3.4 AD-14 / AD-16 · Story 5.1）。
 *
 * <p>🔴 <b>admin 主题（后台保存弹窗 AB-30）与 Story 6.x（运营通知发送）直接调用本类，不得另写一份。</b>
 * 两处各写一遍必然漂移（大小写、scheme 白名单、长度上限），而两边各自的测试都会是绿的。
 *
 * <ul>
 *   <li>{@link #isWellFormed}：纯格式校验（无 IO），后台表单保存时用；</li>
 *   <li>{@link #isAvailable}：格式 + 运行时可用性（商品须在售），App 候选过滤 / 通知发送前用。</li>
 * </ul>
 * 品类是固定枚举、无下线状态（AD-14），well-formed 即可用。
 *
 * <p>批量场景（候选列表）用 {@link #availableProductTokens} 一次查完再配合
 * {@link #isAvailable(OpsTargetType, String, Set)} 逐条判，口径与单条 {@link #isAvailable(OpsTargetType, String)} 相同。
 */
@Component
public class OpsTargetPolicy {

    /** 与 {@code target_value VARCHAR(512)} 一致。 */
    static final int MAX_VALUE_LENGTH = 512;

    private static final Pattern PRODUCT_TOKEN = Pattern.compile("^[0-9A-Za-z_-]{1,32}$");

    private static final Set<String> CATEGORIES = Arrays.stream(ProductCategory.values())
            .map(Enum::name).collect(Collectors.toUnmodifiableSet());

    private static final Set<String> APP_PAGES = Arrays.stream(OpsAppPage.values())
            .map(Enum::name).collect(Collectors.toUnmodifiableSet());

    private final ShopProductRepository products;

    public OpsTargetPolicy(ShopProductRepository products) {
        this.products = products;
    }

    /**
     * 目标格式是否合法。{@code NONE} ⇔ {@code value == null}；其余类型 value 必须非空且符合各自规则。
     * 枚举名区分大小写（{@code home} 非法）。
     */
    public static boolean isWellFormed(OpsTargetType type, String value) {
        if (type == null) {
            return false;
        }
        if (type == OpsTargetType.NONE) {
            return value == null;
        }
        if (value == null) {
            return false;
        }
        return switch (type) {
            case PRODUCT -> PRODUCT_TOKEN.matcher(value).matches();
            case CATEGORY -> CATEGORIES.contains(value);
            case APP_PAGE -> APP_PAGES.contains(value);
            case EXTERNAL -> isHttpUrl(value);
            default -> false;
        };
    }

    /**
     * 目标当前是否可用：先 {@link #isWellFormed}；{@code PRODUCT} 再要求商品存在且 {@code is_active = true}。
     */
    public boolean isAvailable(OpsTargetType type, String value) {
        if (!isWellFormed(type, value)) {
            return false;
        }
        if (type == OpsTargetType.PRODUCT) {
            return products.findByPublicTokenAndActiveTrue(value).isPresent();
        }
        return true;
    }

    /**
     * 批量口径：{@code tokens} 中当前在售（存在且 {@code is_active = true}）的商品 token。
     * 格式非法的 token 不进查询；空入参不查库。
     */
    public Set<String> availableProductTokens(Collection<String> tokens) {
        List<String> wellFormed = tokens.stream()
                .filter(t -> t != null && PRODUCT_TOKEN.matcher(t).matches())
                .distinct()
                .toList();
        if (wellFormed.isEmpty()) {
            return Set.of();
        }
        return products.findActivePublicTokens(wellFormed);
    }

    /**
     * 与 {@link #isAvailable(OpsTargetType, String)} 同一口径，只是商品在售集合由调用方预先批量查好
     * （{@link #availableProductTokens}）。
     */
    public static boolean isAvailable(OpsTargetType type, String value, Set<String> availableProductTokens) {
        if (!isWellFormed(type, value)) {
            return false;
        }
        return type != OpsTargetType.PRODUCT || availableProductTokens.contains(value);
    }

    /**
     * 是否为可下发的 https 地址（运营弹窗图片用）：同 {@code EXTERNAL} 的解析与 host 校验，但<b>只放 https</b>。
     */
    public static boolean isHttpsUrl(String value) {
        return value != null && isHttpUrl(value)
                && "https".equalsIgnoreCase(URI.create(value).getScheme());
    }

    /** 只放 http / https（scheme 忽略大小写）且 host 非空；{@code javascript:} / {@code ftp:} / 相对地址一律拒。 */
    private static boolean isHttpUrl(String value) {
        if (value.length() > MAX_VALUE_LENGTH) {
            return false;
        }
        URI uri;
        try {
            uri = new URI(value);
        } catch (URISyntaxException e) {
            return false;
        }
        String scheme = uri.getScheme();
        if (scheme == null || !("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))) {
            return false;
        }
        String host = uri.getHost();
        return host != null && !host.isBlank();
    }
}
