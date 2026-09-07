package com.tailtopia.admin.shop.dto;

import com.tailtopia.shop.domain.AgeStage;
import com.tailtopia.shop.domain.BodySize;
import com.tailtopia.shop.domain.FeedingGuideEntry;
import com.tailtopia.shop.domain.ProductCategory;
import com.tailtopia.shop.domain.ReturnPolicy;
import com.tailtopia.shop.domain.Species;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 商品创建/编辑表单（Story 1.3，AB-10A）。
 *
 * <p>图片字段承载 OSS <b>对象 key</b>（前端经直传上传后回填 key），<b>绝不</b>承载 URL 或签名 URL。
 * 校验在 service 层（照 {@code VetQualificationService.requireFullInput} 范式）。
 *
 * <p>🔴 {@code feedingGuide} 是 <b>FR-109 粮量见底预估的唯一计算依据</b>，必须结构化。
 * 表单以「体重区间行编辑器」录入（每行三个数字输入），<b>不提供自由文本框</b>。
 */
public class ShopProductForm {

    private String name;
    private String brand;
    private ProductCategory category;
    private String mainImageKey;

    /**
     * 主图原始宽高（2026-08-27）。由上传控件回填的隐藏字段带上来。
     *
     * <p>⚠️ 手填 objectKey 那条兜底路径**给不出尺寸** ⇒ 两者为 null ⇒
     * 客户端按未知比例走占位兜底。这是可接受的降级，不是错误。
     */
    private Integer mainImageW;

    private Integer mainImageH;
    /** 图集 objectKey，逗号/换行分隔的原始输入（照 QualificationForm.specialtiesRaw 范式）。 */
    private String galleryKeysRaw;
    private Species species;
    private BodySize bodySize;
    private AgeStage ageStage;
    private String detailHtml;
    private String shelfLifeNote;
    private ReturnPolicy returnPolicy = ReturnPolicy.NO_RETURN_AFTER_OPEN;
    private int sortWeight;

    /** 体重区间行编辑器绑定：三个平行数组，index 对齐。 */
    private List<Integer> feedWeightMinKg = new ArrayList<>();
    private List<Integer> feedWeightMaxKg = new ArrayList<>();
    private List<Integer> feedGramsPerDay = new ArrayList<>();

    public List<String> galleryKeys() {
        if (galleryKeysRaw == null || galleryKeysRaw.isBlank()) {
            return List.of();
        }
        return Arrays.stream(galleryKeysRaw.split("[,\n]"))
                .map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    /** 把三个平行数组组装成结构化数组；空行（三值皆 null）跳过。 */
    public List<FeedingGuideEntry> feedingGuide() {
        List<FeedingGuideEntry> out = new ArrayList<>();
        int rows = Math.max(feedWeightMinKg.size(),
                Math.max(feedWeightMaxKg.size(), feedGramsPerDay.size()));
        for (int i = 0; i < rows; i++) {
            Integer min = at(feedWeightMinKg, i);
            Integer max = at(feedWeightMaxKg, i);
            Integer g = at(feedGramsPerDay, i);
            if (min == null && max == null && g == null) {
                continue;
            }
            out.add(new FeedingGuideEntry(
                    min == null ? 0 : min, max == null ? 0 : max, g == null ? 0 : g));
        }
        return out;
    }

    private static Integer at(List<Integer> list, int i) {
        return i < list.size() ? list.get(i) : null;
    }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getBrand() { return brand; }
    public void setBrand(String brand) { this.brand = brand; }
    public ProductCategory getCategory() { return category; }
    public void setCategory(ProductCategory category) { this.category = category; }
    public Integer getMainImageW() { return mainImageW; }
    public void setMainImageW(Integer v) { this.mainImageW = v; }
    public Integer getMainImageH() { return mainImageH; }
    public void setMainImageH(Integer v) { this.mainImageH = v; }
    public String getMainImageKey() { return mainImageKey; }
    public void setMainImageKey(String mainImageKey) { this.mainImageKey = mainImageKey; }
    public String getGalleryKeysRaw() { return galleryKeysRaw; }
    public void setGalleryKeysRaw(String v) { this.galleryKeysRaw = v; }
    public Species getSpecies() { return species; }
    public void setSpecies(Species species) { this.species = species; }
    public BodySize getBodySize() { return bodySize; }
    public void setBodySize(BodySize bodySize) { this.bodySize = bodySize; }
    public AgeStage getAgeStage() { return ageStage; }
    public void setAgeStage(AgeStage ageStage) { this.ageStage = ageStage; }
    public String getDetailHtml() { return detailHtml; }
    public void setDetailHtml(String detailHtml) { this.detailHtml = detailHtml; }
    public String getShelfLifeNote() { return shelfLifeNote; }
    public void setShelfLifeNote(String v) { this.shelfLifeNote = v; }
    public ReturnPolicy getReturnPolicy() { return returnPolicy; }
    public void setReturnPolicy(ReturnPolicy v) { this.returnPolicy = v; }
    public int getSortWeight() { return sortWeight; }
    public void setSortWeight(int sortWeight) { this.sortWeight = sortWeight; }
    public List<Integer> getFeedWeightMinKg() { return feedWeightMinKg; }
    public void setFeedWeightMinKg(List<Integer> v) { this.feedWeightMinKg = v; }
    public List<Integer> getFeedWeightMaxKg() { return feedWeightMaxKg; }
    public void setFeedWeightMaxKg(List<Integer> v) { this.feedWeightMaxKg = v; }
    public List<Integer> getFeedGramsPerDay() { return feedGramsPerDay; }
    public void setFeedGramsPerDay(List<Integer> v) { this.feedGramsPerDay = v; }
}
