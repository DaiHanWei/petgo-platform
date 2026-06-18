# Deferred Work

延后处理项（评审中发现、非当前 story 阻断）。

## 2026-06-03 · spec-ui-mockup-alignment 评审遗留
- **问诊严重度胶囊配色一致性**：`triage_page.dart` `_SeverityChip` 的 GREEN/YELLOW 用实底+白字（沿用 `_FreeBadge` 既有模式），而 `UX_DESIGN.md:44-47` 为 green/yellow 定义的是浅底+深字对（`triage-green-bg`/`triage-green-text`）。RED 实底白字与稿一致。非阻断（在 spec Design Notes「三色映射」授权范围内）；YELLOW(#E0A458)+白字对比度可能 <AA 4.5:1，后续可改浅底深字并补 token。

## OSS 媒体地基（spec-2-1-oss-single-bucket-l2.md step-04 审查带出，既有/非本次引入）

> 来源：Story 2.1 单桶适配 L2 审查。以下为 review 暴露的既有债，非本次改动引入，不阻断本次验收。

- **前端 `OssUploader.put` 非 2xx 上传错误无领域映射**：OSS 直传返回 403/404/3xx 时 dio 裸抛 `DioException`，未 try/catch 映射为本地化上传失败（UX「底部 toast 3s」）。本次新增 public-read ACL 使 403 更易触发，但错误处理是既有路径。补全：在 `put`/`MediaUploadUseCase` 包装 OSS 失败为领域错误 + i18n toast；`followRedirects:false` 对 3xx 显式失败。
- **直传无 Content-MD5 完整性校验**：OSS V1 签名串 Content-MD5 位恒空（既有），在途字节截断/篡改 OSS 不拒收。V1 可接受；如需强一致补传 Content-MD5。
