package com.tailtopia.admin.opspopup.dto;

/**
 * 运营弹窗新建 / 编辑表单（V1.3.4 AB-30）。
 *
 * <p>🔴 <b>全部用 String 接</b>（含优先级、天数、时间、枚举）：交给 Spring 绑定的话，
 * 「abc」「2026-13-01T25:00」「FOO」都会在绑定阶段变成 BindException，出来的是
 * 「priority: Failed to convert…」这种没法给运营看的英文串，且不挂错误码、标不了红。
 * 收成字符串、在 {@code AdminOpsPopupService} 里逐项解析，每种错都有自己的 {@code admin.err.opsPopup.*}。
 *
 * <p>{@code startsAt / endsAt} 是 {@code datetime-local} 的值（{@code yyyy-MM-ddTHH:mm}，无时区），按 WIB 解释。
 * {@code imageUrlId / imageUrlEn} 承载上传控件回填的公开桶 objectKey（或 https 完整地址）。
 * {@code targetValue}：跳转类型切换时前端只启用当前类型那一个输入，其余禁用不提交；NONE 时不提交。
 */
public class OpsPopupForm {

    private String name;
    private String priority;
    private String startsAt;
    private String endsAt;
    private String audience;
    private String newUserDays;
    private String frequency;
    private String imageUrlId;
    private String imageUrlEn;
    private String titleId;
    private String titleEn;
    private String bodyId;
    private String bodyEn;
    private String buttonTextId;
    private String buttonTextEn;
    private String targetType;
    private String targetValue;

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getPriority() {
        return priority;
    }

    public void setPriority(String priority) {
        this.priority = priority;
    }

    public String getStartsAt() {
        return startsAt;
    }

    public void setStartsAt(String startsAt) {
        this.startsAt = startsAt;
    }

    public String getEndsAt() {
        return endsAt;
    }

    public void setEndsAt(String endsAt) {
        this.endsAt = endsAt;
    }

    public String getAudience() {
        return audience;
    }

    public void setAudience(String audience) {
        this.audience = audience;
    }

    public String getNewUserDays() {
        return newUserDays;
    }

    public void setNewUserDays(String newUserDays) {
        this.newUserDays = newUserDays;
    }

    public String getFrequency() {
        return frequency;
    }

    public void setFrequency(String frequency) {
        this.frequency = frequency;
    }

    public String getImageUrlId() {
        return imageUrlId;
    }

    public void setImageUrlId(String imageUrlId) {
        this.imageUrlId = imageUrlId;
    }

    public String getImageUrlEn() {
        return imageUrlEn;
    }

    public void setImageUrlEn(String imageUrlEn) {
        this.imageUrlEn = imageUrlEn;
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

    public String getButtonTextId() {
        return buttonTextId;
    }

    public void setButtonTextId(String buttonTextId) {
        this.buttonTextId = buttonTextId;
    }

    public String getButtonTextEn() {
        return buttonTextEn;
    }

    public void setButtonTextEn(String buttonTextEn) {
        this.buttonTextEn = buttonTextEn;
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
}
