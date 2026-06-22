import 'dart:io';

import 'package:flutter/material.dart';

/// 统一图片源解析（Story 通用）。支持三类来源 URL：
/// - `asset:<path>` → 打包资源（演示/假数据宠物照片，`assets/seed/*`）。
/// - `file:<path>` 或绝对本地路径（`/...`）→ 设备本地文件（发布时刚选/拍的图，mock 上传回灌）。
/// - `http(s)://...` → 网络图（真实后端 OSS 签名 URL / 公开桶 CDN）。
///
/// 让 Feed 卡片、内容详情轮播、头像等在 mock 演示（asset/file）与真实后端（network）下用同一套渲染。
class AppImage {
  AppImage._();

  /// 默认缩略图质量（OSS `quality,q_`）。80 在体积/观感上对列表场景平衡较好。
  static const int defaultThumbQuality = 80;

  /// 列表/网格场景的 OSS 缩略图 URL（按需实时缩放，CDN 缓存）。
  ///
  /// 仅对**阿里云 OSS 网络图**生效：给原图追加 `?x-oss-process=image/resize,w_<width>/format,jpg/quality,q_<q>`，
  /// 让 OSS 在分发时缩放重编码——Feed 卡片拿几十 KB 缩略图而非整图，首屏更快；`format,jpg` 顺带去 EXIF
  /// （与决策 E4 隐私兜底一致）。[width] 为**物理像素**（调用方按显示宽度 × devicePixelRatio 传入）。
  ///
  /// 以下情形原样返回，绝不破坏 URL：非 http(s)（asset/file）、非 OSS 域（如 Google 头像
  /// `googleusercontent.com`）、已带 `x-oss-process` 的（如对外名片去 EXIF 图）、[width] ≤ 0。
  static String ossResized(String url, {required int width, int quality = defaultThumbQuality}) {
    if (width <= 0) return url;
    final uri = Uri.tryParse(url);
    if (uri == null || (uri.scheme != 'http' && uri.scheme != 'https')) return url;
    if (!uri.host.endsWith('aliyuncs.com')) return url;
    if (url.contains('x-oss-process')) return url;
    final sep = url.contains('?') ? '&' : '?';
    // 串接而非 Uri.replace：保留 process 值里的 `/` `,` 字面量（OSS 要求不转义）。
    return '$url${sep}x-oss-process=image/resize,w_$width/format,jpg/quality,q_$quality';
  }

  /// 渲染为 [Widget]（封面/轮播用）。[errorBuilder] 缺省回退到调用方传入的占位。
  ///
  /// [thumbWidth]（物理像素）非空时，**仅网络 OSS 图**经 [ossResized] 取缩略图（列表/网格用）；
  /// asset/file 本地图与非 OSS 网络图不受影响。
  static Widget widget(
    String url, {
    Key? key,
    double? width,
    double? height,
    BoxFit fit = BoxFit.cover,
    int? thumbWidth,
    Widget Function(BuildContext, Object, StackTrace?)? errorBuilder,
  }) {
    if (url.startsWith('asset:')) {
      return Image.asset(url.substring(6),
          key: key, width: width, height: height, fit: fit, errorBuilder: errorBuilder);
    }
    if (url.startsWith('file:')) {
      return Image.file(File(url.substring(5)),
          key: key, width: width, height: height, fit: fit, errorBuilder: errorBuilder);
    }
    if (url.startsWith('/')) {
      return Image.file(File(url),
          key: key, width: width, height: height, fit: fit, errorBuilder: errorBuilder);
    }
    final net = thumbWidth == null ? url : ossResized(url, width: thumbWidth);
    return Image.network(net,
        key: key, width: width, height: height, fit: fit, errorBuilder: errorBuilder);
  }

  /// 渲染为 [ImageProvider]（头像 CircleAvatar.backgroundImage 用）；空/null → null。
  ///
  /// [thumbWidth]（物理像素）非空时，网络 OSS 图取缩略图（小头像省流量）。
  static ImageProvider? provider(String? url, {int? thumbWidth}) {
    if (url == null || url.isEmpty) return null;
    if (url.startsWith('asset:')) return AssetImage(url.substring(6));
    if (url.startsWith('file:')) return FileImage(File(url.substring(5)));
    if (url.startsWith('/')) return FileImage(File(url));
    return NetworkImage(thumbWidth == null ? url : ossResized(url, width: thumbWidth));
  }
}
