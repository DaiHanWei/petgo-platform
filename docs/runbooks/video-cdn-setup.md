# 视频 CDN 接入（专题线 video · Story 3.4）

> 架构 AD-11、决策 V-38；PRD §10 第 2 条（**阻塞生产上线**）、第 3 条（存储生命周期，待定案）。
> 开发与 stag 不依赖 CDN：不配 `MEDIA_VIDEO_CDN_BASE_URL` 时，视频直接走 OSS 源站地址，缓存刷新是空操作（Noop）。

## 0. 前提

1. **阿里云企业实名认证**（印尼公司主体）。阿里云 CDN 只对企业客户开放，认证前无法开通。
2. 视频与图片在同一个 OSS 桶（见 `oss-setup.md`）。视频对象 key 为 `video/<userId>/<随机>.mp4`；
   staging 与生产共桶时 staging 带前缀 `stag/`（`MEDIA_OSS_KEY_PREFIX`），即 `stag/video/...`。
3. **封面 / 图片不走视频 CDN**（V-38）：仍用 `OSS_CDN_BASE_URL`。只有 `video.url` 改用 CDN 域名。

## 1. CDN 加速域名配置清单

| 项 | 配置 | 原因 |
|---|---|---|
| 加速域名 | 如 `v.<主域名>`，HTTPS 证书 | App 只播 HTTPS |
| 源站 | OSS 域名（本桶），回源 HOST = 桶域名 | |
| **私有 Bucket 回源** | **关闭** | 下架的视频文件被改成私有后，CDN 回源必须拿到 403，否则下架等于没下架 |
| 访问控制 · 路径白名单 | 只放行 `^/(stag/)?video/[^/]+/[^/]+\.mp4$`，其余 403。控制台没有叫「路径白名单」的现成开关：用**规则引擎**（条件：URI 不匹配该正则 → 拒绝访问）或 **EdgeScript** 实现 | 视频域名不能被拿来读图片 / 其他对象 |
| 回源协议 | HTTPS（跟随或强制 HTTPS 回源） | |
| Range 回源 | 开启 | 播放器分段请求、拖动进度 |
| 响应头 | `X-Content-Type-Options: nosniff` | 防止被当成别的类型渲染 |
| IP 访问频控 | 开启（阈值按运营量定） | 防刷流量 |
| 日流量告警 + 封顶 | 开启，超封顶自动下线域名 | 成本兜底 |
| 缓存过期 | `.mp4` 可长缓存（文件不覆盖：上传策略禁止覆盖） | 下架靠主动刷新，不靠过期 —— **所以只要域名还在服务，就必须有真实刷新器在跑**（见 §4） |
| 状态码过期 | 4xx（尤其 403）不缓存或 ≤ 10 秒 | 恢复后的帖子不被缓存住的 403 卡住 |

## 2. RAM 权限

后端 AccessKey（`ALIYUN_ACCESS_KEY_ID/SECRET`）在现有 OSS 权限之外，**只追加** `cdn:RefreshObjectCaches`：

```json
{
  "Version": "1",
  "Statement": [
    { "Effect": "Allow", "Action": ["cdn:RefreshObjectCaches"], "Resource": "*" }
  ]
}
```

## 3. 后端环境变量

```
MEDIA_VIDEO_CDN_BASE_URL=https://v.<主域名>
```

- 启动时 `MediaConfig` 用 `StringUtils.hasText` 判断：有值 → `AliyunCdnCacheRefresher`；空串 / 未配 → `NoopCdnCacheRefresher`
  （**不用 `@ConditionalOnProperty`**：yml 默认值是空串，它会误判为「有」）。
- 下发：`video.url = <CDN 域名>/<视频 key>`；库里只存 key，**加 / 去 CDN 都不需要数据迁移**。
- 刷新：文件公开状态对账器（`MediaAclReconciler`，默认每分钟，另有下架 / 恢复等接口的即时对账）改完 OSS ACL 后，
  调 `RefreshObjectCaches(ObjectType=File, ObjectPath=<CDN 视频地址>)`。刷新失败抛出 → 本条不回写、下一轮重试。

## 4. 回退 / 换域名

> 🔴 **不能只删环境变量就走**：删掉后刷新器变成 Noop，此后下架的帖子只改 OSS 为私有、不刷 CDN；
> 已经下发出去的旧 CDN 地址仍从长缓存命中 200，直到缓存过期 —— 违反 NFR-6「下架后 10 分钟内不可访问」。

回退顺序：

1. 在 CDN 控制台**先停用该加速域名**（或至少对 `video/`、`stag/video/` 做一次**目录刷新**，并在回退期间保持停用）；
2. 再删掉 `MEDIA_VIDEO_CDN_BASE_URL` 并重启：视频地址回到 OSS 源站，刷新变为 Noop。无需改库；
3. 之后重新启用：先启用域名、再配回变量重启（App 侧新下发的地址才会换回 CDN）。

换 CDN 域名同理：后端只会刷新**当前**域名的缓存，旧域名要先停用或整目录刷新。

## 5. L2 验收（接入 CDN 的环境）

记 `V=https://v.<主域名>/video/<uid>/<key>.mp4`（一条已公开的视频帖）。

```bash
# 1) 200 + Accept-Ranges + nosniff
curl -sI "$V" | grep -iE '^(HTTP|accept-ranges|x-content-type-options)'
# 2) Range → 206
curl -s -o /dev/null -w '%{http_code}\n' -H 'Range: bytes=0-1023' "$V"
# 3) 非视频路径 → 403（图片路径、以及 video/ 下非 .mp4 的对象）
curl -s -o /dev/null -w '%{http_code}\n' "https://v.<主域名>/public/<uid>/<封面>.jpg"
curl -s -o /dev/null -w '%{http_code}\n' "https://v.<主域名>/video/<uid>/<任意>.txt"
# 3b) stag 前缀（与生产共桶时）：stag 视频可访问
curl -s -o /dev/null -w '%{http_code}\n' "https://v.<主域名>/stag/video/<uid>/<key>.mp4"   # 期望 200
# 4) 后台下架该帖后，10 分钟内（NFR-6）：
curl -s -o /dev/null -w '%{http_code}\n' "$V"   # 期望 403
# 5) 恢复后可再访问（对账 + 刷新后 200）
```

另在阿里云 CDN 控制台「刷新预热 → 操作记录」确认有 `File` 类型的刷新任务，且后端日志无 `video_acl_reconcile_failed`
持续出现（出现即检查 RAM 权限 / 刷新配额）。

**刷新配额**：URL 刷新有每日配额（以控制台「刷新预热」页显示的当日余量为准）。超额后 `RefreshObjectCaches` 报错 →
对账器该条不回写、每分钟重试，持续超过告警阈值会打 `video_acl_reconcile_stuck` ERROR。视频供给初期约每天 1 条（V-41），
正常远低于配额；若出现 stuck，先看配额余量。

## 6. 存储生命周期（PRD §10 第 3 条，待定案）

- stag：规则**只作用于 `stag/video/` 前缀**，不影响 stag 图片。具体天数 / 转低频 / 删除等定案后再配。
- 生产：遵守 F21（文件永不删除），除非 §10 第 3 条另行决定视频例外。
