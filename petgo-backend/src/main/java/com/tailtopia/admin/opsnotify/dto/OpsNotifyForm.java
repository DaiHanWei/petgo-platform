package com.tailtopia.admin.opsnotify.dto;

/**
 * 「新建系统通知」四步页的表单（V1.3.4 AB-31；按后台返工清单 §4 对齐 UI 稿 I3–I8 · D-16）。
 *
 * <p>🔴 <b>全部用 String 接</b>（预设天数、布尔三态、枚举、ID 文本）：交给 Spring 绑定的话「abc」会在绑定阶段变成
 * BindException，出来的是没法给运营看的英文串、不挂错误码。收成字符串、在 {@code AdminOpsNotifyService} 里逐项解析。
 *
 * <ul>
 *   <li>① 内容：{@code titleId / titleEn / bodyId / bodyEn}；{@code targetType / targetValue}（必选，无默认）；</li>
 *   <li>② 选择用户：{@code audienceMode} = ALL / FILTER / IDS；
 *       FILTER 六个下拉 —— {@code registeredWithin / activeWithin}（空 = 不限，7 / 30 / 90 = 近 N 天，§0-5）、
 *       {@code hasPet / hasShopOrder / hasPhone}（空 = 不限，true / false）、{@code species}（单选 CAT / DOG / OTHER，§0-6）；
 *       IDS —— {@code userIds}（一行一个 ID，粘贴或由 CSV 读入）+ {@code idsSource}（PASTE / CSV）；</li>
 *   <li>③ 发送时间：{@code sendMode} = NOW / SCHEDULED；{@code scheduledAt}（{@code datetime-local}，按 WIB 解释）；</li>
 *   <li>④ 确认：{@code previewCount}（确认页上的预计人数，服务端重算比对）、{@code confirmCount}（大范围发送时 I8
 *       弹层里手输的人数）、{@code submitKey}（打开新建页时发的一次性提交键）。</li>
 * </ul>
 * 未选中的人群块前端置 disabled 不提交；服务端也只读当前模式用得到的字段。
 */
public class OpsNotifyForm {

    private String titleId;
    private String titleEn;
    private String bodyId;
    private String bodyEn;
    private String audienceMode;
    private String registeredWithin;
    private String activeWithin;
    private String hasPet;
    private String species;
    private String hasShopOrder;
    private String hasPhone;
    private String userIds;
    private String idsSource;
    private String targetType;
    private String targetValue;
    private String sendMode;
    private String scheduledAt;
    private String previewCount;
    private String confirmCount;
    private String submitKey;

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

    public String getRegisteredWithin() {
        return registeredWithin;
    }

    public void setRegisteredWithin(String registeredWithin) {
        this.registeredWithin = registeredWithin;
    }

    public String getActiveWithin() {
        return activeWithin;
    }

    public void setActiveWithin(String activeWithin) {
        this.activeWithin = activeWithin;
    }

    public String getHasPet() {
        return hasPet;
    }

    public void setHasPet(String hasPet) {
        this.hasPet = hasPet;
    }

    public String getSpecies() {
        return species;
    }

    public void setSpecies(String species) {
        this.species = species;
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

    public String getIdsSource() {
        return idsSource;
    }

    public void setIdsSource(String idsSource) {
        this.idsSource = idsSource;
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

    public String getSendMode() {
        return sendMode;
    }

    public void setSendMode(String sendMode) {
        this.sendMode = sendMode;
    }

    public String getScheduledAt() {
        return scheduledAt;
    }

    public void setScheduledAt(String scheduledAt) {
        this.scheduledAt = scheduledAt;
    }

    public String getPreviewCount() {
        return previewCount;
    }

    public void setPreviewCount(String previewCount) {
        this.previewCount = previewCount;
    }

    public String getConfirmCount() {
        return confirmCount;
    }

    public void setConfirmCount(String confirmCount) {
        this.confirmCount = confirmCount;
    }

    public String getSubmitKey() {
        return submitKey;
    }

    public void setSubmitKey(String submitKey) {
        this.submitKey = submitKey;
    }
}
