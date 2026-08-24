package com.tailtopia.content.larksync;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Lark 开放平台客户端（spec-lark-scheduled-posts）——本功能所有 Lark API 的单一收口。
 *
 * <p>四类能力：tenant_access_token（内存缓存，过期前 5 分钟主动刷新）、读表格值域、
 * 按行回写「上传状态/发布账号」、云盘文件夹列表 + 文件下载。全部经 Spring {@link RestClient}，
 * 显式超时（连接 5s / 读 {@code timeoutSeconds}s，图片下载可能数 MB）。
 *
 * <p>护栏：appSecret 仅本类持有，<b>绝不落日志</b>；Lark 响应一律先验 {@code code==0}，
 * 非 0 抛 {@link LarkApiException}。{@link LarkApiException} 的语义是<b>传输/平台层失败</b>
 * （token、权限、网络、接口错）——上层据此中止本轮等下小时，绝不据此把内容行标 FAILED
 * （内容性失败如「缺图」由上层自行判定，不经本异常）。
 *
 * <p>任一 API 失败会同时作废 token 缓存：密钥轮换/token 提前吊销时，下次调用即重新换取，
 * 不会拿着死 token 撞到本地过期时刻（最长 2h）。
 */
@Component
public class LarkContentClient {

    /** 单图字节上限：超过按平台异常处理（正常商详图远小于此，超限多半是错误体/异常文件）。 */
    private static final int MAX_IMAGE_BYTES = 15 * 1024 * 1024;

    private final LarkContentSyncProperties props;
    private final RestClient rest;

    /** token 缓存（单实例部署 + 每小时一次调用，synchronized 足够）。 */
    private String cachedToken;
    private Instant tokenExpireAt = Instant.EPOCH;

    public LarkContentClient(LarkContentSyncProperties props) {
        this.props = props;
        SimpleClientHttpRequestFactory rf = new SimpleClientHttpRequestFactory();
        rf.setConnectTimeout(Duration.ofSeconds(5));
        rf.setReadTimeout(Duration.ofSeconds(Math.max(1, props.getTimeoutSeconds())));
        this.rest = RestClient.builder().baseUrl(props.getBaseUrl()).requestFactory(rf).build();
    }

    /** Lark 传输/平台层失败（非 0 code、权限、网络、疑似错误体）——上层应中止本轮，勿标行 FAILED。 */
    public static class LarkApiException extends RuntimeException {
        public LarkApiException(String message) {
            super(message);
        }
    }

    // ===== token =====

    /** 取 tenant_access_token，缓存至官方过期时刻前 5 分钟。 */
    synchronized String tenantToken() {
        if (cachedToken != null && Instant.now().isBefore(tokenExpireAt)) {
            return cachedToken;
        }
        JsonNode resp = rest.post()
                .uri("/open-apis/auth/v3/tenant_access_token/internal")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("app_id", props.getAppId(), "app_secret", props.getAppSecret()))
                .retrieve()
                .body(JsonNode.class);
        ensureOk(resp, "tenant_access_token");
        cachedToken = resp.path("tenant_access_token").asText();
        long expireSeconds = resp.path("expire").asLong(7200);
        tokenExpireAt = Instant.now().plusSeconds(Math.max(60, expireSeconds - 300));
        return cachedToken;
    }

    private synchronized void invalidateToken() {
        cachedToken = null;
        tokenExpireAt = Instant.EPOCH;
    }

    // ===== 表格 =====

    /**
     * 读数据区（A2:F{rowLimit+1}）。返回原始行（cell 统一转字符串，null 单元格 → ""）。
     * 外层 index 0 对应表格第 2 行——行号换算归 {@link LarkRowParser}。
     */
    public List<List<String>> readRows() {
        String range = props.getSheetId() + "!A2:F" + (props.getRowLimit() + 1);
        JsonNode resp = rest.get()
                .uri("/open-apis/sheets/v2/spreadsheets/{token}/values/{range}?valueRenderOption=ToString",
                        props.getSpreadsheetToken(), range)
                .header("Authorization", "Bearer " + tenantToken())
                .retrieve()
                .body(JsonNode.class);
        ensureOk(resp, "读取表格");
        JsonNode values = resp.path("data").path("valueRange").path("values");
        List<List<String>> rows = new java.util.ArrayList<>();
        for (JsonNode row : values) {
            List<String> cells = new java.util.ArrayList<>();
            for (JsonNode cell : row) {
                cells.add(cell.isNull() ? "" : cell.asText().trim());
            }
            rows.add(cells);
        }
        return rows;
    }

    /**
     * 回写前按「内容编号」重定位行号：开轮快照与回写之间运营可能插/删行，
     * 按快照行号盲写会标错行。返回当前 B 列中第一个等于 {@code contentCode} 的表格行号。
     */
    public Optional<Integer> findRowByCode(String contentCode) {
        String range = props.getSheetId() + "!B2:B" + (props.getRowLimit() + 1);
        JsonNode resp = rest.get()
                .uri("/open-apis/sheets/v2/spreadsheets/{token}/values/{range}?valueRenderOption=ToString",
                        props.getSpreadsheetToken(), range)
                .header("Authorization", "Bearer " + tenantToken())
                .retrieve()
                .body(JsonNode.class);
        ensureOk(resp, "定位行");
        JsonNode values = resp.path("data").path("valueRange").path("values");
        int i = 0;
        for (JsonNode row : values) {
            String cell = row.size() > 0 && !row.get(0).isNull() ? row.get(0).asText().trim() : "";
            if (contentCode.equals(cell)) {
                return Optional.of(i + 2);
            }
            i++;
        }
        return Optional.empty();
    }

    /**
     * 回写某一行的「上传状态(E) / 发布账号(F)」。{@code sheetRowNumber} 是表格实际行号
     * （数据区第一条 = 2）。
     */
    public void writeStatus(int sheetRowNumber, String status, String account) {
        String range = props.getSheetId() + "!E" + sheetRowNumber + ":F" + sheetRowNumber;
        JsonNode resp = rest.put()
                .uri("/open-apis/sheets/v2/spreadsheets/{token}/values", props.getSpreadsheetToken())
                .header("Authorization", "Bearer " + tenantToken())
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("valueRange", Map.of(
                        "range", range,
                        "values", List.of(List.of(status, account)))))
                .retrieve()
                .body(JsonNode.class);
        ensureOk(resp, "回写表格");
    }

    // ===== 云盘 =====

    /** 列图片文件夹全部文件：文件名 → file_token（处理分页；page_token 经 queryParam 正确编码）。 */
    public Map<String, String> listFolderFiles() {
        Map<String, String> files = new LinkedHashMap<>();
        String pageToken = null;
        do {
            final String pt = pageToken;
            JsonNode resp = rest.get()
                    .uri(b -> {
                        b.path("/open-apis/drive/v1/files")
                                .queryParam("folder_token", props.getFolderToken())
                                .queryParam("page_size", 200);
                        if (pt != null) {
                            b.queryParam("page_token", pt);
                        }
                        return b.build();
                    })
                    .header("Authorization", "Bearer " + tenantToken())
                    .retrieve()
                    .body(JsonNode.class);
            ensureOk(resp, "列云盘文件夹");
            for (JsonNode f : resp.path("data").path("files")) {
                if ("file".equals(f.path("type").asText())) {
                    files.put(f.path("name").asText(), f.path("token").asText());
                }
            }
            pageToken = resp.path("data").path("has_more").asBoolean(false)
                    ? resp.path("data").path("next_page_token").asText(null)
                    : null;
        } while (pageToken != null && !pageToken.isBlank());
        return files;
    }

    /**
     * 下载云盘文件字节（图片转存 OSS 用）。Lark 网关对权限/文件异常可能回
     * HTTP 200 + JSON 错误体——按图片魔数校验兜底，非图片字节一律按平台异常处理，
     * 绝不让错误体以 image/jpeg 之名传上公开桶发出去。
     */
    public byte[] downloadFile(String fileToken) {
        byte[] bytes = rest.get()
                .uri("/open-apis/drive/v1/files/{token}/download", fileToken)
                .header("Authorization", "Bearer " + tenantToken())
                .retrieve()
                .body(byte[].class);
        if (bytes == null || bytes.length == 0) {
            throw new LarkApiException("下载文件为空 token=" + fileToken);
        }
        if (bytes.length > MAX_IMAGE_BYTES) {
            throw new LarkApiException("下载文件超限 " + bytes.length + "B token=" + fileToken);
        }
        if (!looksLikeImage(bytes)) {
            throw new LarkApiException("下载内容非图片（疑似错误体）token=" + fileToken);
        }
        return bytes;
    }

    /** JPEG/PNG/GIF/WEBP 魔数。 */
    private static boolean looksLikeImage(byte[] b) {
        if (b.length < 12) {
            return false;
        }
        if ((b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8) {
            return true; // JPEG
        }
        if ((b[0] & 0xFF) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G') {
            return true; // PNG
        }
        if (b[0] == 'G' && b[1] == 'I' && b[2] == 'F') {
            return true; // GIF
        }
        return b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[3] == 'F'
                && b[8] == 'W' && b[9] == 'E' && b[10] == 'B' && b[11] == 'P'; // WEBP
    }

    private void ensureOk(JsonNode resp, String action) {
        if (resp == null) {
            invalidateToken();
            throw new LarkApiException(action + " 响应为空");
        }
        int code = resp.path("code").asInt(-1);
        if (code != 0) {
            // 保守起见任何非 0 都作废 token 缓存：代价只是下轮多换一次 token，
            // 换来密钥轮换/提前吊销场景的自愈。msg 是 Lark 错误描述，不含凭证，可安全带出。
            invalidateToken();
            throw new LarkApiException(action + " 失败 code=" + code + " msg=" + resp.path("msg").asText());
        }
    }
}
