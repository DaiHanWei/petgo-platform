/// 法务 H5 公开地址（单一事实源）。
///
/// 默认指向已上线的 `legal.tailtopia.id`（后端 [LegalPageController] 直出静态页，
/// 经 Cloudflare Tunnel 暴露）。打包时仍可用 `--dart-define` 覆盖：
/// `--dart-define=PETGO_PRIVACY_URL=… --dart-define=PETGO_TERMS_URL=…`。
const String kPrivacyUrl = String.fromEnvironment(
  'PETGO_PRIVACY_URL',
  defaultValue: 'https://legal.tailtopia.id/privacy',
);

const String kTermsUrl = String.fromEnvironment(
  'PETGO_TERMS_URL',
  defaultValue: 'https://legal.tailtopia.id/terms',
);
