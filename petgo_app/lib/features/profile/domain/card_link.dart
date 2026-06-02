/// 名片 H5 对外分享链接（Story 2.6 · F1 契约对齐）。
///
/// 后端 Thymeleaf 直出 `GET /p/{cardToken}`；分享按钮（Story 2.7）用此 URL。
/// base host 经 `--dart-define=PETGO_H5_BASE_URL` 注入（默认占位）。
const String kH5BaseUrl =
    String.fromEnvironment('PETGO_H5_BASE_URL', defaultValue: 'https://petgo.example');

/// 由不可枚举 cardToken 拼出对外名片 URL（绝不暴露顺序 id）。
String petCardShareUrl(String cardToken, {String baseUrl = kH5BaseUrl}) {
  final trimmed = baseUrl.endsWith('/') ? baseUrl.substring(0, baseUrl.length - 1) : baseUrl;
  return '$trimmed/p/$cardToken';
}
