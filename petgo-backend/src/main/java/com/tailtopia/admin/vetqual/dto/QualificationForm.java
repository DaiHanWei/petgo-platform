package com.tailtopia.admin.vetqual.dto;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import org.springframework.format.annotation.DateTimeFormat;

/**
 * 资质录入/续期表单（Story 2.7）。证件图字段为 OSS 私密桶**对象 key**（前端经直传上传后回填 key）；
 * **绝不**承载签名 URL。校验在 service 层（直录要全、续期要有效期+SIPDH 证件图）。
 */
public class QualificationForm {

    private String ktpNo;
    private String ktpPhotoKey;
    private String sipdhNo;
    private String sipdhIssuer;
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate sipdhExpiry;
    private String sipdhPhotoKey;
    // STRV（兽医注册证）可选字段（Bug 166）。
    private String strvNo;
    private String strvIssuer;
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate strvExpiry;
    private String strvPhotoKey;
    private String degreePhotoKey;
    private String profilePhotoKey;
    private String pdhiPhotoKey;
    /** 专长，逗号/换行分隔的原始输入。 */
    private String specialtiesRaw;

    public List<String> specialties() {
        if (specialtiesRaw == null || specialtiesRaw.isBlank()) {
            return List.of();
        }
        return Arrays.stream(specialtiesRaw.split("[,\\n]"))
                .map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    public String getKtpNo() { return ktpNo; }
    public void setKtpNo(String v) { this.ktpNo = v; }
    public String getKtpPhotoKey() { return ktpPhotoKey; }
    public void setKtpPhotoKey(String v) { this.ktpPhotoKey = v; }
    public String getSipdhNo() { return sipdhNo; }
    public void setSipdhNo(String v) { this.sipdhNo = v; }
    public String getSipdhIssuer() { return sipdhIssuer; }
    public void setSipdhIssuer(String v) { this.sipdhIssuer = v; }
    public LocalDate getSipdhExpiry() { return sipdhExpiry; }
    public void setSipdhExpiry(LocalDate v) { this.sipdhExpiry = v; }
    public String getSipdhPhotoKey() { return sipdhPhotoKey; }
    public void setSipdhPhotoKey(String v) { this.sipdhPhotoKey = v; }
    public String getStrvNo() { return strvNo; }
    public void setStrvNo(String v) { this.strvNo = v; }
    public String getStrvIssuer() { return strvIssuer; }
    public void setStrvIssuer(String v) { this.strvIssuer = v; }
    public LocalDate getStrvExpiry() { return strvExpiry; }
    public void setStrvExpiry(LocalDate v) { this.strvExpiry = v; }
    public String getStrvPhotoKey() { return strvPhotoKey; }
    public void setStrvPhotoKey(String v) { this.strvPhotoKey = v; }
    public String getDegreePhotoKey() { return degreePhotoKey; }
    public void setDegreePhotoKey(String v) { this.degreePhotoKey = v; }
    public String getProfilePhotoKey() { return profilePhotoKey; }
    public void setProfilePhotoKey(String v) { this.profilePhotoKey = v; }
    public String getPdhiPhotoKey() { return pdhiPhotoKey; }
    public void setPdhiPhotoKey(String v) { this.pdhiPhotoKey = v; }
    public String getSpecialtiesRaw() { return specialtiesRaw; }
    public void setSpecialtiesRaw(String v) { this.specialtiesRaw = v; }
}
