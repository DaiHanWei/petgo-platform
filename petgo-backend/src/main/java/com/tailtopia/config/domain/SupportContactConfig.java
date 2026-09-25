package com.tailtopia.config.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * 客服联系方式配置（Story 3-1 / AD-S8）。固定单行 {@code id=1}（DB CHECK 保证）。
 *
 * <p>种子 = 现网正在用的号码，行为零变化。改后台配置即时生效：**不需发版、不需重启**。
 *
 * <p>🔴 <b>只存运营输入的原样号码，不存 E.164</b>。E.164 形态由
 * {@code IndonesiaPhone.normalize} 读时派生 —— 两份都落库必然走散，
 * 而走散之后「展示的号码」和「深链拨出去的号码」就是两个人。
 */
@Entity
@Table(name = "support_contact_config")
public class SupportContactConfig {

    public static final long SINGLETON_ID = 1L;

    @Id
    private Long id;

    @Column(name = "whatsapp_number", nullable = false, length = 20)
    private String whatsappNumber;

    @Column(name = "email", nullable = false, length = 120)
    private String email;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected SupportContactConfig() {
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public String getWhatsappNumber() {
        return whatsappNumber;
    }

    public String getEmail() {
        return email;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    /** 运营改配置（校验与留痕在 {@code AdminConfigService}，本方法只改值）。 */
    public void update(String whatsappNumber, String email) {
        this.whatsappNumber = whatsappNumber;
        this.email = email;
    }
}
