/// 版本更新图标（V1.3.4 Story 3.2，UI 稿 C1 横幅 / C2 弹窗同一图形：手机 + 下载箭头）。
///
/// SVG 内 stroke 写死 `#000000` 仅作占位，颜色一律由 `SvgPicture.string(..., colorFilter:)` 着色
/// （弹窗白、横幅 `AppColors.mint`）。
library;

/// 弹窗用（stroke-width 1.8）。
const String kUpdatePhoneSvgDialog = '<svg viewBox="0 0 24 24" fill="none" stroke="#000000" '
    'stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">'
    '<rect x="6" y="2.5" width="12" height="19" rx="2.5"/>'
    '<path d="M12 8v6M9.5 11.5 12 14l2.5-2.5"/></svg>';

/// 横幅用（stroke-width 2，Story 3.3）。
const String kUpdatePhoneSvgBanner = '<svg viewBox="0 0 24 24" fill="none" stroke="#000000" '
    'stroke-width="2" stroke-linecap="round" stroke-linejoin="round">'
    '<rect x="6" y="2.5" width="12" height="19" rx="2.5"/>'
    '<path d="M12 8v6M9.5 11.5 12 14l2.5-2.5"/></svg>';
