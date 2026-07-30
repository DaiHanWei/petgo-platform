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

/// 兽医合作方（Mitra）条款（bug 20260722-354）。占位默认 `mitra-terms`，
/// 待法务 H5 页就绪后由后端 [LegalPageController] 直出或用 dart-define 覆盖。
const String kVetTermsUrl = String.fromEnvironment(
  'PETGO_VET_TERMS_URL',
  defaultValue: 'https://legal.tailtopia.id/mitra-terms',
);
