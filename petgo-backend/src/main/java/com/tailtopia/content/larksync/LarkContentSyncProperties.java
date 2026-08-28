package com.tailtopia.content.larksync;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Lark 定时发帖配置（spec-lark-scheduled-posts）。前缀 {@code petgo.lark-content}。
 *
 * <p>护栏：{@code appSecret} 从 env（{@code LARK_CONTENT_APP_SECRET}）注入，<b>绝不入库、绝不落日志</b>；
 * {@code .env.example} 仅放占位。{@code mode=off}（默认）任务体直接返回、零外部调用，
 * 未配置的环境（本地/CI/prod 未开）即安全静默；{@code mode=live} 才真打 Lark API。
 */
@ConfigurationProperties(prefix = "petgo.lark-content")
public class LarkContentSyncProperties {

    /** {@code off} | {@code live}。默认 off，合并到任何环境都不会意外跑。 */
    private String mode = "off";

    /** Lark 企业自建应用凭证（与 bug bot 同一应用，独立 env 键注入）。 */
    private String appId = "";

    /** 应用密钥（env 注入，绝不入库/落日志）。 */
    private String appSecret = "";

    /** 开放平台域名。海外租户 open.larksuite.com；国内换 open.feishu.cn。 */
    private String baseUrl = "https://open.larksuite.com";

    /** 内容表格 spreadsheet token（《List of Content for Automatic Upload》）。 */
    private String spreadsheetToken = "";

    /** 工作表 sheetId（非标题；如 6a32cd）。 */
    private String sheetId = "";

    /** 图片云盘文件夹 token（文件名 = {图片编号}.jpg）。 */
    private String folderToken = "";

    /** 读取的数据行上限（表格从第 2 行起为数据）。 */
    private int rowLimit = 500;

    /** 发帖作者池：虚拟账号 users.id，逗号分隔 env 注入；每次发布随机取一。 */
    private List<Long> authorIds = List.of(
            1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L, 10L,
            11L, 12L, 13L, 14L, 15L, 16L, 17L, 18L, 19L, 20L);

    /** 单次 HTTP 读超时（秒）。图片下载可能数 MB，给足余量。 */
    private int timeoutSeconds = 30;

    public String getMode() {
        return mode;
    }

    public void setMode(String mode) {
        this.mode = mode;
    }

    public boolean isLive() {
        return "live".equalsIgnoreCase(mode);
    }

    public String getAppId() {
        return appId;
    }

    public void setAppId(String appId) {
        this.appId = appId;
    }

    public String getAppSecret() {
        return appSecret;
    }

    public void setAppSecret(String appSecret) {
        this.appSecret = appSecret;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getSpreadsheetToken() {
        return spreadsheetToken;
    }

    public void setSpreadsheetToken(String spreadsheetToken) {
        this.spreadsheetToken = spreadsheetToken;
    }

    public String getSheetId() {
        return sheetId;
    }

    public void setSheetId(String sheetId) {
        this.sheetId = sheetId;
    }

    public String getFolderToken() {
        return folderToken;
    }

    public void setFolderToken(String folderToken) {
        this.folderToken = folderToken;
    }

    public int getRowLimit() {
        return rowLimit;
    }

    public void setRowLimit(int rowLimit) {
        this.rowLimit = rowLimit;
    }

    public List<Long> getAuthorIds() {
        return authorIds;
    }

    public void setAuthorIds(List<Long> authorIds) {
        this.authorIds = authorIds;
    }

    public int getTimeoutSeconds() {
        return timeoutSeconds;
    }

    public void setTimeoutSeconds(int timeoutSeconds) {
        this.timeoutSeconds = timeoutSeconds;
    }
}
