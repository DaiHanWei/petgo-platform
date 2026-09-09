package com.tailtopia.admin.refund.service;

import com.tailtopia.shared.error.AppException;
import com.tailtopia.shared.media.AliyunOssClient;
import com.tailtopia.shared.media.SignedUrlService;
import java.io.IOException;
import java.util.Locale;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

/**
 * 出款凭证上传（V1.3.0 Story 2.8，D-36）。走既有对象存储通道，落<b>私密桶</b>（凭证含转账信息，不能 public-read），
 * 只返回 objectKey；展示时按 key 现签短 TTL URL（{@link SignedUrlService}）。objectKey 进审计 detail，URL 不落库不进日志。
 */
@Service
public class AdminRefundProofService {

    static final long MAX_BYTES = 10L * 1024 * 1024;

    private final AliyunOssClient oss;
    private final SignedUrlService signedUrls;

    public AdminRefundProofService(AliyunOssClient oss, SignedUrlService signedUrls) {
        this.oss = oss;
        this.signedUrls = signedUrls;
    }

    /** 上传凭证图（jpeg / png / pdf，≤10MB），返回 objectKey。 */
    public String upload(String refundToken, MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw AppException.validation("请附上出款凭证").code("admin.err.refund.proofRequired");
        }
        if (file.getSize() > MAX_BYTES) {
            throw AppException.validation("凭证文件超过 10MB").code("admin.err.refund.proofTooLarge");
        }
        String ext = switch (String.valueOf(file.getContentType()).toLowerCase(Locale.ROOT)) {
            case "image/jpeg" -> "jpg";
            case "image/png" -> "png";
            case "application/pdf" -> "pdf";
            // 只信客户端 Content-Type（后台内部上传，无 magic-byte 嗅探）；不收 webp（SDK 后缀推断 Content-Type 未核实）
            default -> throw AppException.validation("凭证仅支持 JPG / PNG / PDF").code("admin.err.refund.proofType");
        };
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            throw AppException.validation("凭证读取失败，请重试").code("admin.err.refund.proofReadFailed");
        }
        String key = "refund-proof/" + refundToken + "/" + UUID.randomUUID() + "." + ext;
        oss.putPrivateObject(key, bytes);
        return key;
    }

    /** 凭证展示：短 TTL 签名 URL（不缓存、不入库、不落日志）；无 key → null。 */
    public String viewUrl(String objectKey) {
        return objectKey == null || objectKey.isBlank() ? null : signedUrls.sign(objectKey);
    }
}
