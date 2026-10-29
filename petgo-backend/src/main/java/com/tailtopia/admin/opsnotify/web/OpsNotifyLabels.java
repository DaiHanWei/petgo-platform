package com.tailtopia.admin.opsnotify.web;

import com.tailtopia.admin.opsnotify.service.AdminOpsNotifyService;
import com.tailtopia.notify.ops.domain.AudienceSpec;
import com.tailtopia.ops.target.OpsTargetType;
import com.tailtopia.profile.domain.PetType;
import com.tailtopia.shared.i18n.Messages;
import java.net.URI;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * 「系统通知发送」的可读摘要（V1.3.4 AB-31 返工 · UI 稿 I1 / I7）：人群一句话、跳转一句话。
 * 列表与确认页共用，免得两处说法不一。全部走 {@link Messages}（后台当前语言）。
 */
@Component
public class OpsNotifyLabels {

    private final Messages msg;

    public OpsNotifyLabels(Messages msg) {
        this.msg = msg;
    }

    /**
     * 人群：「全部用户」/「按条件：近 30 天活跃 · 有宠物档案 · 猫」/「指定用户」/「指定用户（CSV）」。
     * 条件顺序同新建页六个下拉；存量行的绝对日期键显示成区间。
     */
    public String audience(AudienceSpec spec, String idsSource) {
        if (spec == null) {
            return "—";
        }
        return switch (spec.mode()) {
            case ALL -> msg.get("admin.opsNotify.audience.ALL");
            case IDS -> AdminOpsNotifyService.SOURCE_CSV.equals(idsSource)
                    ? msg.get("admin.opsNotify.audience.IDS_CSV") : msg.get("admin.opsNotify.audience.IDS");
            case FILTER -> msg.get("admin.opsNotify.audience.filterPrefix", conditions(spec));
        };
    }

    private String conditions(AudienceSpec s) {
        List<String> parts = new ArrayList<>();
        if (s.registeredWithinDays() != null) {
            parts.add(msg.get("admin.opsNotify.cond.registeredWithin", s.registeredWithinDays()));
        }
        if (s.registeredFrom() != null || s.registeredTo() != null) {
            parts.add(msg.get("admin.opsNotify.cond.registeredRange", day(s.registeredFrom()), day(s.registeredTo())));
        }
        if (s.activeWithinDays() != null) {
            parts.add(msg.get("admin.opsNotify.cond.activeWithin", s.activeWithinDays()));
        }
        if (s.activeFrom() != null || s.activeTo() != null) {
            parts.add(msg.get("admin.opsNotify.cond.activeRange", day(s.activeFrom()), day(s.activeTo())));
        }
        if (s.activity() != null) {
            for (var a : s.activity()) {
                parts.add(msg.get("admin.opsNotify.activity." + a.name()));
            }
        }
        if (s.levelTiers() != null) {
            for (var t : s.levelTiers()) {
                parts.add(msg.get("admin.opsNotify.levelTier." + t.name()));
            }
        }
        if (s.hasPet() != null) {
            parts.add(msg.get("admin.opsNotify.cond.hasPet." + s.hasPet()));
        }
        if (s.species() != null) {
            for (PetType t : s.species()) {
                parts.add(msg.get("admin.opsNotify.species." + t.name()));
            }
        }
        if (s.hasShopOrder() != null) {
            parts.add(msg.get("admin.opsNotify.cond.hasShopOrder." + s.hasShopOrder()));
        }
        if (s.hasPhone() != null) {
            parts.add(msg.get("admin.opsNotify.cond.hasPhone." + s.hasPhone()));
        }
        if (s.paid() != null) {
            parts.add(msg.get("admin.opsNotify.cond.paid." + s.paid()));
        }
        return parts.isEmpty() ? msg.get("admin.opsNotify.audience.filterNone") : String.join(" · ", parts);
    }

    private static String day(LocalDate d) {
        return d == null ? "…" : d.toString();
    }

    /** 跳转：「外部链接 · promo.example.co.id」/「App 内页面 · 首页」/「品类 · 主粮」/「商品 · token」/「无跳转」。 */
    public String target(OpsTargetType type, String value) {
        String typeLabel = msg.get("admin.opsPopups.targetType." + type.name());
        String v = switch (type) {
            case NONE -> null;
            case APP_PAGE -> msg.get("admin.opsPopups.appPage." + value);
            case CATEGORY -> msg.get("admin.v130.shopProducts.category." + value);
            case EXTERNAL -> host(value);
            default -> value;
        };
        return v == null ? typeLabel : msg.get("admin.opsNotify.target.summary", typeLabel, v);
    }

    /** 外链只显示域名（UI 稿 I7）；解析不了就原样。 */
    private static String host(String url) {
        try {
            String h = URI.create(url).getHost();
            return h == null ? url : h;
        } catch (RuntimeException e) {
            return url;
        }
    }
}
