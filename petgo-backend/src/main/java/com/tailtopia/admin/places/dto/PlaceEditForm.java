package com.tailtopia.admin.places.dto;

import com.tailtopia.shared.error.AppException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * 场所编辑表单（V1.3.0 Story 5.3 AC1；5.4 新建复用）。标记人不在表单里（服务端也不接收）。
 * {@code tags} 由逗号 / 空格分隔的原文解析为去重列表（UPPER_SNAKE），值域校验取 App 侧 {@code PlaceTag}（2026-09-18 场所表对齐）。
 */
public record PlaceEditForm(String name, String placeType, List<String> tags, String description, String city, String addressText,
        BigDecimal lat, BigDecimal lng) {

    public static final int NAME_MAX = 80;
    public static final int ADDRESS_MAX = 255;
    public static final int CITY_MAX = 60;
    public static final int TAGS_MAX = 20;

    /** 标签值域 = App 侧 {@link com.tailtopia.place.domain.PlaceTag}（唯一定义处）。 */
    public static final List<String> KNOWN_TAGS = java.util.Arrays.stream(com.tailtopia.place.domain.PlaceTag.values())
            .map(Enum::name).toList();

    /** 解析并做长度 / 必填校验（坐标范围交 {@code PlaceCoordinateValidator}）。 */
    public static PlaceEditForm of(String name, String placeType, String tagsRaw, String description, String city, String addressText,
            String latRaw, String lngRaw) {
        String n = trim(name);
        if (n == null || n.length() > NAME_MAX) {
            throw AppException.validation("名称需为 1～80 字").code("admin.err.places.nameInvalid");
        }
        String t = trim(placeType);
        // 对齐后（D1）类型全集已知，表上有 ck_places_type —— 未知值在这里拒，别让它撞 CHECK 出 500。
        if (t == null || !com.tailtopia.admin.places.domain.PlaceType.isKnown(t.toUpperCase())) {
            throw AppException.validation("请选择类型").code("admin.err.places.typeInvalid");
        }
        String c = trim(city);
        if (c == null || c.length() > CITY_MAX) {
            throw AppException.validation("城市需为 1～60 字").code("admin.err.places.cityInvalid");
        }
        String a = trim(addressText);
        if (a == null || a.length() > ADDRESS_MAX) {
            throw AppException.validation("文字地址需为 1～255 字").code("admin.err.places.addressInvalid");
        }
        return new PlaceEditForm(n, t.toUpperCase(), parseTags(tagsRaw), trim(description), c, a, parseCoord(latRaw, "admin.err.places.latRange"),
                parseCoord(lngRaw, "admin.err.places.lngRange"));
    }

    private static String trim(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }

    private static BigDecimal parseCoord(String raw, String code) {
        try {
            return raw == null || raw.isBlank() ? null : new BigDecimal(raw.strip());
        } catch (NumberFormatException e) {
            throw AppException.validation("坐标须为数字").code(code);
        }
    }

    static List<String> parseTags(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (String s : raw.split("[,，\\s]+")) {
            String v = s.strip();
            if (!v.isEmpty()) {
                out.add(v.toUpperCase());
            }
        }
        if (out.size() > TAGS_MAX) {
            throw AppException.validation("标签最多 20 个").code("admin.err.places.tagsTooMany");
        }
        // 🔴 2026-09-18 场所表对齐：App 把 tags 当枚举列表读（List<PlaceTag>），
        //    写进一个 App 不认识的标签，App 读这个场所时反序列化失败 → 列表 / 详情整页 500。
        //    值域直接取 App 的枚举（唯一定义处），不在后台另抄一份。
        for (String v : out) {
            if (!KNOWN_TAGS.contains(v)) {
                throw AppException.validation("未知标签：" + v)
                        .code("admin.err.places.tagUnknown", v, String.join(", ", KNOWN_TAGS));
            }
        }
        return new ArrayList<>(out);
    }

    /** 表单回填用：逗号分隔。 */
    public String tagsJoined() {
        return String.join(", ", tags);
    }
}
