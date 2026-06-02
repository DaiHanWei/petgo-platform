package com.petgo.profile.service;

import com.petgo.profile.domain.PetProfile;
import com.petgo.shared.media.AliyunOssClient;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import javax.imageio.ImageIO;
import org.springframework.stereotype.Service;

/**
 * OG 预渲染静态图（Story 2.6 · B3）。社交预览大图（含名字+品种），生成卡片/编辑时预渲染，
 * 存①公开桶 + CDN，落 {@code pet_profiles.og_image_url}。重渲染由 2.8 编辑成功后调用。
 *
 * <p>实现：JDK 内置 {@link BufferedImage}/{@link ImageIO}（无重依赖，headless 可跑/可单测）。
 * {@link #render} 纯生成字节（L0 可测）；{@link #regenerate} 额外上传公开桶（L2 真实网络）。
 */
@Service
public class OgImageService {

    static final int WIDTH = 1200;
    static final int HEIGHT = 630;

    private final AliyunOssClient ossClient;
    private final ProfileService profileService;

    public OgImageService(AliyunOssClient ossClient, ProfileService profileService) {
        this.ossClient = ossClient;
        this.profileService = profileService;
    }

    /** 渲染 OG 卡片 PNG 字节（名字 + 品种）。纯函数，L0 可测。 */
    public byte[] render(String name, String breed) {
        BufferedImage img = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(new Color(0xFA, 0xF8, 0xF5)); // 品牌底色
            g.fillRect(0, 0, WIDTH, HEIGHT);
            g.setColor(new Color(0xC8, 0x87, 0x4A)); // accentGrowth
            g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 96));
            g.drawString(safe(name), 80, 320);
            if (breed != null && !breed.isBlank()) {
                g.setColor(new Color(0x9B, 0x92, 0x8A));
                g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 48));
                g.drawString(breed, 80, 400);
            }
            g.setColor(new Color(0x9B, 0x92, 0x8A));
            g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 36));
            g.drawString("PetGo", 80, 560);
        } finally {
            g.dispose();
        }
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ImageIO.write(img, "png", baos);
            return baos.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * 重渲染并上传到公开桶，回填 {@code og_image_url}（L2 真实网络）。供 2.8 编辑成功后调用。
     */
    public String regenerate(PetProfile profile) {
        byte[] png = render(profile.getName(), profile.getBreed());
        String key = "public/og/" + profile.getId() + "/" + profile.getCardToken() + ".png";
        String url = ossClient.putPublicObject(key, png, "image/png");
        profileService.updateOgImageUrl(profile.getId(), url);
        return url;
    }

    private static String safe(String s) {
        return (s == null || s.isBlank()) ? "PetGo" : s;
    }
}
