package com.tailtopia.admin.dto;

import com.tailtopia.content.domain.ContentType;
import com.tailtopia.content.domain.ImageSize;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.ArrayList;
import java.util.List;

/**
 * 种子内容发布表单（Story 3.1；V1.1.6 Story 12.2 加发布账号与上传结果）。
 *
 * <p>{@code imageUrlsRaw} 为多行文本（每行一个公开桶 URL），服务端拆分校验 ≤9 张。
 *
 * <p>🔴 <b>V1.1.6 Story 12.2 起 author 来自表单</b>（{@link #authorUserId}），
 * 不再写死为"登录后台账号所关联的官方作者身份"。原先那个写死的行为有两个后果：
 * ① 运营只能以那一个身份发内容；② 成长日历（{@code GROWTH_MOMENT}）<b>实际发不出来</b> ——
 * 它必须绑一份宠物档案，而那个官方作者账号没有档案。
 *
 * <p>🛡 <b>"不信任客户端 author" 这条原则没有被放弃，只是换了守法</b>：
 * 服务端校验该账号<b>在运营发布身份池内</b>，且以运营真实账号发布还要
 * {@code seed.publish_as_real} 权限（Story 12.1）。
 */
public class SeedPostForm {

    @NotNull(message = "请选择内容类型")
    private ContentType type;

    /**
     * 发布账号（Story 12.2 · AC1）。必填 —— 🛡 <b>刻意没有默认值</b>：
     * 默认预选等于手滑直接提交时用的是"上次那个号"，而以运营真实账号误发不可撤回。
     */
    @NotNull(message = "请选择发布账号")
    private Long authorUserId;

    /**
     * 上传得到的原始宽高，每行 {@code w x h}，与 {@link #imageUrlsRaw} <b>同序等长</b>。
     *
     * <p>🔴 为什么要带这个：后端对无尺寸的图会异步下载再量一遍，
     * 那意味着"刚发完就刷首页"的人看到的仍是占位比例（Story 3.5 记过同一笔账）。
     * 上传时本来就在本地量过了，顺手带上来即可。
     *
     * <p>⚠️ 长度与 URL 不符时**整组丢弃**（走异步兜底），绝不"跳过不放" ——
     * 后端对长度不符的处理就是整组作废，错位比没有更糟。
     */
    private String imageSizesRaw;

    /** 仅 GROWTH_MOMENT 需绑定（属运营账号的宠物档案）；其余类型留空。 */
    private Long petId;

    /**
     * 关联物种（V1.1.6 Story 14.1 · AC4）。
     *
     * <p>界面上默认跟随所选发布账号的账号物种定位，运营可当场覆盖。
     * 🔴 发布账号为**运营真实账号**时默认**留空** —— 它有真实宠物档案，
     * 让算法读档案比让运营猜一个准确。
     */
    private String species;

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

    public Long getAuthorUserId() {
        return authorUserId;
    }

    public void setAuthorUserId(Long authorUserId) {
        this.authorUserId = authorUserId;
    }

    public String getImageSizesRaw() {
        return imageSizesRaw;
    }

    public void setImageSizesRaw(String imageSizesRaw) {
        this.imageSizesRaw = imageSizesRaw;
    }

    public String getSpecies() {
        return species;
    }

    public void setSpecies(String species) {
        this.species = species;
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

    /**
     * 上传时量到的原始宽高（与 {@link #imageUrls()} 同序等长）。
     *
     * <p>🛡 <b>长度不符 / 解析失败 ⇒ 整组返回 null</b>。
     *
     * <p>⚠️ 「长度不符 → 整组作废」这条规则的**权威实现在 {@code ImageSizeResolver#normalize}**，
     * 本方法这一层是刻意重复的一道 —— 但目的不是"多一层保险"（反证已确认删掉它
     * <b>不改变发布结果</b>），而是**为了那条日志**：normalize 在长度不符时会打
     * {@code WARN「客户端算错了长度，属实现 bug 而非用户行为」}，
     * 而后台这一侧长度不符<b>恰恰是用户行为</b>（运营在兜底 URL 框里多填了一行）。
     * 先归一成 null，normalize 就走"没报尺寸"那条静默分支，日志不被污染。
     * 由 {@code SeedPostFormImageSizesTest} 钉住（L1 钉不住它）。
     */
    public List<ImageSize> imageSizes() {
        List<String> urls = imageUrls();
        if (urls == null || imageSizesRaw == null || imageSizesRaw.isBlank()) {
            return null;
        }
        List<ImageSize> out = new ArrayList<>();
        for (String line : imageSizesRaw.split("\\R")) {
            String t = line.trim();
            if (t.isEmpty()) {
                continue;
            }
            String[] wh = t.split("[xX]");
            if (wh.length != 2) {
                return null;
            }
            try {
                out.add(new ImageSize(Integer.parseInt(wh[0].trim()), Integer.parseInt(wh[1].trim())));
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return out.size() == urls.size() ? out : null;
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
