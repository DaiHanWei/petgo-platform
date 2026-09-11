/// App 下载页地址（单一事实源）。
///
/// V1.3.0 批次 A · Story 5.2 · AC7：年龄卡底部品牌带的二维码指向**这里**。
///
/// 🔴 **走配置项、不硬编码、不带 token**（AD-A19）：
/// - 年龄卡是**通用**卡片，扫码的人不是来看某一条内容的，落到下载页才对
///   —— 这一点与内容分享卡刚好相反（那边的码必须指向那条内容的分享链接）。
/// - 不带 token：这张图会被发到 Stories 上给陌生人看，任何随图外流的标识都是隐患。
///
/// 打包时可用 `--dart-define=PETGO_APP_DOWNLOAD_URL=…` 覆盖。
const String kAppDownloadUrl = String.fromEnvironment(
  'PETGO_APP_DOWNLOAD_URL',
  defaultValue: 'https://tailtopia.id/download',
);
