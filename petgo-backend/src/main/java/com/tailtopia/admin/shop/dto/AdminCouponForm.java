package com.tailtopia.admin.shop.dto;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * 后台优惠券抽屉表单（V1.4.2 shop-admin Story 2.2 · A3~A8）。只是绑定载体，转换与校验在
 * {@code AdminShopCouponService} / {@code CouponAdminService}。
 *
 * <p>日期按 WIB 自然日录入：开始日 00:00 起、结束日<b>含当天</b>（存库为次日 00:00 的左闭右开）。
 * 发放总量留空 = 不限；兑换码方式 {@code codeMode}：{@code UNIVERSAL} 通用码（填 {@code universalCode}）/
 * {@code BATCH} 批量一次性码（数量 = 发放总量）。
 */
public class AdminCouponForm {

    private String kind = "DISCOUNT";
    private String nameId;
    private String nameEn;
    private Long amount;
    private Long minSpend;
    private String acquireType = "CLAIM";
    private String codeMode = "UNIVERSAL";
    private String universalCode;
    @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE)
    private LocalDate issueStart;
    @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE)
    private LocalDate issueEnd;
    private String validMode = "RELATIVE";
    @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE)
    private LocalDate validStart;
    @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE)
    private LocalDate validEnd;
    private Integer validDays;
    private Integer totalQuantity;
    private Integer perUserLimit = 1;
    private String scopeType = "ALL";
    private List<String> scopeCategories = new ArrayList<>();
    private String scopeProducts;

    public String getKind() {
        return kind;
    }

    public void setKind(String kind) {
        this.kind = kind;
    }

    public String getNameId() {
        return nameId;
    }

    public void setNameId(String nameId) {
        this.nameId = nameId;
    }

    public String getNameEn() {
        return nameEn;
    }

    public void setNameEn(String nameEn) {
        this.nameEn = nameEn;
    }

    public Long getAmount() {
        return amount;
    }

    public void setAmount(Long amount) {
        this.amount = amount;
    }

    public Long getMinSpend() {
        return minSpend;
    }

    public void setMinSpend(Long minSpend) {
        this.minSpend = minSpend;
    }

    public String getAcquireType() {
        return acquireType;
    }

    public void setAcquireType(String acquireType) {
        this.acquireType = acquireType;
    }

    public String getCodeMode() {
        return codeMode;
    }

    public void setCodeMode(String codeMode) {
        this.codeMode = codeMode;
    }

    public String getUniversalCode() {
        return universalCode;
    }

    public void setUniversalCode(String universalCode) {
        this.universalCode = universalCode;
    }

    public LocalDate getIssueStart() {
        return issueStart;
    }

    public void setIssueStart(LocalDate issueStart) {
        this.issueStart = issueStart;
    }

    public LocalDate getIssueEnd() {
        return issueEnd;
    }

    public void setIssueEnd(LocalDate issueEnd) {
        this.issueEnd = issueEnd;
    }

    public String getValidMode() {
        return validMode;
    }

    public void setValidMode(String validMode) {
        this.validMode = validMode;
    }

    public LocalDate getValidStart() {
        return validStart;
    }

    public void setValidStart(LocalDate validStart) {
        this.validStart = validStart;
    }

    public LocalDate getValidEnd() {
        return validEnd;
    }

    public void setValidEnd(LocalDate validEnd) {
        this.validEnd = validEnd;
    }

    public Integer getValidDays() {
        return validDays;
    }

    public void setValidDays(Integer validDays) {
        this.validDays = validDays;
    }

    public Integer getTotalQuantity() {
        return totalQuantity;
    }

    public void setTotalQuantity(Integer totalQuantity) {
        this.totalQuantity = totalQuantity;
    }

    public Integer getPerUserLimit() {
        return perUserLimit;
    }

    public void setPerUserLimit(Integer perUserLimit) {
        this.perUserLimit = perUserLimit;
    }

    public String getScopeType() {
        return scopeType;
    }

    public void setScopeType(String scopeType) {
        this.scopeType = scopeType;
    }

    public List<String> getScopeCategories() {
        return scopeCategories;
    }

    public void setScopeCategories(List<String> scopeCategories) {
        this.scopeCategories = scopeCategories == null ? new ArrayList<>() : scopeCategories;
    }

    public String getScopeProducts() {
        return scopeProducts;
    }

    public void setScopeProducts(String scopeProducts) {
        this.scopeProducts = scopeProducts;
    }
}
