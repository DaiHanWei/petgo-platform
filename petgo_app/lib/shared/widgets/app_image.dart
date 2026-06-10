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

  /// 渲染为 [Widget]（封面/轮播用）。[errorBuilder] 缺省回退到调用方传入的占位。
  static Widget widget(
    String url, {
    Key? key,
    double? width,
    double? height,
    BoxFit fit = BoxFit.cover,
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
    return Image.network(url,
        key: key, width: width, height: height, fit: fit, errorBuilder: errorBuilder);
  }

  /// 渲染为 [ImageProvider]（头像 CircleAvatar.backgroundImage 用）；空/null → null。
  static ImageProvider? provider(String? url) {
    if (url == null || url.isEmpty) return null;
    if (url.startsWith('asset:')) return AssetImage(url.substring(6));
    if (url.startsWith('file:')) return FileImage(File(url.substring(5)));
    if (url.startsWith('/')) return FileImage(File(url));
    return NetworkImage(url);
  }
}
