package com.tailtopia.admin.places.dto;

import com.tailtopia.shared.error.AppException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * 场所编辑表单（V1.3.0 Story 5.3 AC1；5.4 新建复用）。标记人不在表单里（服务端也不接收）。
 * {@code tags} 由逗号 / 空格分隔的原文解析为去重列表（UPPER_SNAKE 原样，不做值域校验——值域由 App 端定）。
 */
public record PlaceEditForm(String name, String placeType, List<String> tags, String description, String city, String addressText,
        BigDecimal lat, BigDecimal lng) {

    public static final int NAME_MAX = 80;
    public static final int ADDRESS_MAX = 255;
    public static final int CITY_MAX = 60;
    public static final int TAGS_MAX = 20;

    /** 解析并做长度 / 必填校验（坐标范围交 {@code PlaceCoordinateValidator}）。 */
    public static PlaceEditForm of(String name, String placeType, String tagsRaw, String description, String city, String addressText,
            String latRaw, String lngRaw) {
        String n = trim(name);
        if (n == null || n.length() > NAME_MAX) {
            throw AppException.validation("名称需为 1～80 字").code("admin.err.places.nameInvalid");
        }
        String t = trim(placeType);
        if (t == null || t.length() > 32) {
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
        return new ArrayList<>(out);
    }

    /** 表单回填用：逗号分隔。 */
    public String tagsJoined() {
        return String.join(", ", tags);
    }
}
