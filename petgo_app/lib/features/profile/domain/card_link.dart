/// 名片 H5 对外分享链接（Story 2.6 · F1 契约对齐）。
///
/// 后端 Thymeleaf 直出 `GET /p/{cardToken}`（成长故事/档案公开页，已上线于后端容器，
/// 与 api.tailtopia.id 同一服务）；分享按钮（Story 2.7）用此 URL。
/// 默认指向对外分享专用干净子域 `s.tailtopia.id`（Cloudflare 隧道 → 后端 127.0.0.1:8084）。
/// 非用户明确指令不得改动此默认值（与 PETGO_API_BASE_URL 同约定）。可经 `--dart-define=PETGO_H5_BASE_URL` 覆盖。
const String kH5BaseUrl =
    String.fromEnvironment('PETGO_H5_BASE_URL', defaultValue: 'https://s.tailtopia.id');

/// 由不可枚举 cardToken 拼出对外名片 URL（绝不暴露顺序 id）。
String petCardShareUrl(String cardToken, {String baseUrl = kH5BaseUrl}) {
  final trimmed = baseUrl.endsWith('/') ? baseUrl.substring(0, baseUrl.length - 1) : baseUrl;
  return '$trimmed/p/$cardToken';
}

// 下载引导落地页（后端直出 `GET /get`，扫码平台判断跳商店/唤起 app）**仍在线**，外部渠道可直接用；
// 但 App 内已无引用点——它原本只被 KTP 背面二维码调用，背面于 2026-07-17 按用户决策取消。
// 若日后 App 内再需要拉新二维码/链接，在此重建 petDownloadUrl 即可。

/// 由不可枚举 shareToken 拼出 P-35 里程碑庆祝对外分享 URL（后端 `GET /m/{shareToken}` 直出 H5）。
/// 与 [petCardShareUrl] 同 H5 子域；非用户明确指令不得改默认值。
String milestoneShareUrl(String shareToken, {String baseUrl = kH5BaseUrl}) {
  final trimmed = baseUrl.endsWith('/') ? baseUrl.substring(0, baseUrl.length - 1) : baseUrl;
  return '$trimmed/m/$shareToken';
}

/// 由不可枚举 shareToken 拼出**单条内容**对外分享 URL（后端 `GET /c/{shareToken}` 直出 H5）。
///
/// 🔴 **与 [petCardShareUrl] 是两种不同的链接类型，落地页也不同**（Story 9.3 · AD-15 Rule 5）：
/// - `/p/{cardToken}` → 整本档案的只读视图
/// - `/c/{shareToken}` → **只有被分享的那一条**
///
/// 复用同一落点等于把「我只想分享一条」变成「我把整本都给你了」—— 这是隐私边界，不是路由洁癖。
/// 与前两者同 H5 子域；非用户明确指令不得改默认值。
String postShareUrl(String shareToken, {String baseUrl = kH5BaseUrl}) {
  final trimmed = baseUrl.endsWith('/') ? baseUrl.substring(0, baseUrl.length - 1) : baseUrl;
  return '$trimmed/c/$shareToken';
}
