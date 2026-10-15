package com.tailtopia.admin.opsnotify.dto;

import java.util.List;

/**
 * 运营通知新建表单（V1.3.4 AB-31 · FR-138 · D-16）。
 *
 * <p>🔴 <b>全部用 String 接</b>（日期、布尔三态、枚举、ID 文本）：交给 Spring 绑定的话「abc」「2026-13-01」
 * 都会在绑定阶段变成 BindException，出来的是没法给运营看的英文串、不挂错误码。收成字符串、
 * 在 {@code AdminOpsNotifyService} 里逐项解析，每种错都有自己的码。
 *
 * <ul>
 *   <li>{@code audienceMode}：ALL / FILTER / IDS；</li>
 *   <li>{@code registeredFrom..activeTo}：{@code <input type="date">} 的 {@code yyyy-MM-dd}，按 WIB 自然日含两端，空 = 不限；</li>
 *   <li>{@code hasPet / hasShopOrder / hasPhone}：三态下拉，空 = 不限，{@code true} / {@code false}；</li>
 *   <li>{@code species}：多选（CAT / DOG / OTHER），空 = 不限；</li>
 *   <li>{@code userIds}：粘贴的用户 ID 文本，逗号 / 空格 / 换行分隔；</li>
 *   <li>{@code scheduledAt}：{@code datetime-local}（无时区）按 WIB 解释，空 = 立即发送；</li>
 *   <li>{@code previewCount}：确认框里的预估人数 N（服务端重算比对，缺 = 没预估过）；</li>
 *   <li>{@code submitKey}：抽屉渲染时发的一次性提交键（服务端 Redis 认领，防重复提交）。</li>
 * </ul>
 * 人群三选一时未选中那一块的输入前端置 disabled 不提交；服务端也只读当前模式用得到的字段。
 */
public class OpsNotifyForm {

    private String titleId;
    private String titleEn;
    private String bodyId;
    private String bodyEn;
    private String audienceMode;
    private String registeredFrom;
    private String registeredTo;
    private String activeFrom;
    private String activeTo;
    private String hasPet;
    private String hasShopOrder;
    private String hasPhone;
    private String userIds;
    private String targetType;
    private String targetValue;
    private String scheduledAt;
    private List<String> species;
    private String previewCount;
    private String submitKey;

    public String getPreviewCount() {
        return previewCount;
    }

    public void setPreviewCount(String previewCount) {
        this.previewCount = previewCount;
    }

    public String getSubmitKey() {
        return submitKey;
    }

    public void setSubmitKey(String submitKey) {
        this.submitKey = submitKey;
    }

    public String getTitleId() {
        return titleId;
    }

    public void setTitleId(String titleId) {
        this.titleId = titleId;
    }

    public String getTitleEn() {
        return titleEn;
    }

    public void setTitleEn(String titleEn) {
        this.titleEn = titleEn;
    }

    public String getBodyId() {
        return bodyId;
    }

    public void setBodyId(String bodyId) {
        this.bodyId = bodyId;
    }

    public String getBodyEn() {
        return bodyEn;
    }

    public void setBodyEn(String bodyEn) {
        this.bodyEn = bodyEn;
    }

    public String getAudienceMode() {
        return audienceMode;
    }

    public void setAudienceMode(String audienceMode) {
        this.audienceMode = audienceMode;
    }

    public String getRegisteredFrom() {
        return registeredFrom;
    }

    public void setRegisteredFrom(String registeredFrom) {
        this.registeredFrom = registeredFrom;
    }

    public String getRegisteredTo() {
        return registeredTo;
    }

    public void setRegisteredTo(String registeredTo) {
        this.registeredTo = registeredTo;
    }

    public String getActiveFrom() {
        return activeFrom;
    }

    public void setActiveFrom(String activeFrom) {
        this.activeFrom = activeFrom;
    }

    public String getActiveTo() {
        return activeTo;
    }

    public void setActiveTo(String activeTo) {
        this.activeTo = activeTo;
    }

    public String getHasPet() {
        return hasPet;
    }

    public void setHasPet(String hasPet) {
        this.hasPet = hasPet;
    }

    public String getHasShopOrder() {
        return hasShopOrder;
    }

    public void setHasShopOrder(String hasShopOrder) {
        this.hasShopOrder = hasShopOrder;
    }

    public String getHasPhone() {
        return hasPhone;
    }

    public void setHasPhone(String hasPhone) {
        this.hasPhone = hasPhone;
    }

    public String getUserIds() {
        return userIds;
    }

    public void setUserIds(String userIds) {
        this.userIds = userIds;
    }

    public String getTargetType() {
        return targetType;
    }

    public void setTargetType(String targetType) {
        this.targetType = targetType;
    }

    public String getTargetValue() {
        return targetValue;
    }

    public void setTargetValue(String targetValue) {
        this.targetValue = targetValue;
    }

    public String getScheduledAt() {
        return scheduledAt;
    }

    public void setScheduledAt(String scheduledAt) {
        this.scheduledAt = scheduledAt;
    }

    public List<String> getSpecies() {
        return species;
    }

    public void setSpecies(List<String> species) {
        this.species = species;
    }
}
