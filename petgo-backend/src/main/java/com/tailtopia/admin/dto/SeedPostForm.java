package com.tailtopia.admin.dto;

import com.tailtopia.content.domain.ContentType;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.ArrayList;
import java.util.List;

/**
 * 种子内容发布表单（Story 3.1，Thymeleaf 表单回填用 getter/setter）。
 *
 * <p>{@code imageUrlsRaw} 为多行文本（每行一个公开桶 URL），服务端拆分校验 ≤9 张。
 * author 取自登录会话不在表单（不信任客户端 author）。
 */
public class SeedPostForm {

    @NotNull(message = "请选择内容类型")
    private ContentType type;

    /** 仅 GROWTH_MOMENT 需绑定（属运营账号的宠物档案）；其余类型留空。 */
    private Long petId;

    @Size(max = 1000, message = "正文不能超过 1000 字")
    private String text;

    /** 每行一个图片 URL；服务端拆分后校验 ≤9 张。 */
    private String imageUrlsRaw;

    public ContentType getType() {
        return type;
    }

    public void setType(ContentType type) {
        this.type = type;
    }

    public Long getPetId() {
        return petId;
    }

    public void setPetId(Long petId) {
        this.petId = petId;
    }

    public String getText() {
        return text;
    }

    public void setText(String text) {
        this.text = text;
    }

    public String getImageUrlsRaw() {
        return imageUrlsRaw;
    }

    public void setImageUrlsRaw(String imageUrlsRaw) {
        this.imageUrlsRaw = imageUrlsRaw;
    }

    /** 多行原始文本 → 去空白的 URL 列表（空列表返回 null，对齐用户帖「无图为 null」）。 */
    public List<String> imageUrls() {
        if (imageUrlsRaw == null || imageUrlsRaw.isBlank()) {
            return null;
        }
        List<String> out = new ArrayList<>();
        for (String line : imageUrlsRaw.split("\\R")) {
            String trimmed = line.trim();
            if (!trimmed.isEmpty()) {
                out.add(trimmed);
            }
        }
        return out.isEmpty() ? null : out;
    }
}
