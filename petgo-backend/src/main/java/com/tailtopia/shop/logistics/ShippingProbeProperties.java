package com.tailtopia.shop.logistics;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 标准探测包裹（v1.4.2 shop Story 1.1，3.1 地址簿 {@code serviceable} 用）：默认 1 件、1,000 g、
 * 20 × 15 × 10 cm。前缀 {@code petgo.shop.shipping-probe}。
 */
@ConfigurationProperties(prefix = "petgo.shop.shipping-probe")
public class ShippingProbeProperties {

    private int weightG = 1000;
    private int lengthCm = 20;
    private int widthCm = 15;
    private int heightCm = 10;

    public PackageItem toItem() {
        return new PackageItem("probe", 100_000L, 1, weightG, lengthCm, widthCm, heightCm);
    }

    public int getWeightG() {
        return weightG;
    }

    public void setWeightG(int weightG) {
        this.weightG = weightG;
    }

    public int getLengthCm() {
        return lengthCm;
    }

    public void setLengthCm(int lengthCm) {
        this.lengthCm = lengthCm;
    }

    public int getWidthCm() {
        return widthCm;
    }

    public void setWidthCm(int widthCm) {
        this.widthCm = widthCm;
    }

    public int getHeightCm() {
        return heightCm;
    }

    public void setHeightCm(int heightCm) {
        this.heightCm = heightCm;
    }
}
