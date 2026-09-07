import 'package:flutter/material.dart';

/// 标签图标（V1.1.6 Story 11.5）—— 用户标签与内容装饰标签共用。
///
/// ## 🔴 值的语义在本 story 变了
/// 后台原先是个**文本框**（运营只能填 emoji），而 FR-74 / FR-75 原文写的是
/// 「自定义**上传**一张小图标」。11.5 把它改成上传，`icon` 因此存**公开图片 URL**。
///
/// ## ⚠️ 过渡兼容，不是双模式
/// 值不以 `http` 开头时按**文本**渲染 —— 本地/测试库里还有 emoji 存量，
/// 生产因功能未发版不会有。🔴 **待确认无非 URL 图标后应删掉这条分支**，
/// 留着它会让"图标到底该是什么"这件事一直含混。
///
/// ## 🛡 加载失败不留碎图
/// 图标挂在对象存储上，网络差时它会晚到甚至不到。那时**整个图标位不占空间**，
/// 让标签只显示名称 —— 而不是留一个碎图框或一块灰底。
/// 名称本身就说明了这是什么标签，图标只是锦上添花。
class TagIcon extends StatelessWidget {
  const TagIcon({super.key, required this.icon, required this.size});

  /// 后端下发的 `icon`：图片 URL，或存量的 emoji 字符。
  final String icon;

  /// 目标边长（逻辑像素）。图标是方的，宽高同值。
  final double size;

  /// 是不是图片 URL（而非存量字符图标）。
  static bool isImage(String value) =>
      value.startsWith('http://') || value.startsWith('https://');

  @override
  Widget build(BuildContext context) {
    if (icon.isEmpty) {
      return const SizedBox.shrink();
    }
    if (!isImage(icon)) {
      // 存量字符图标（emoji）。字号取边长的 0.86 —— 与改造前一致，视觉不跳。
      return SizedBox(
        width: size,
        height: size,
        child: Center(
          child: Text(icon,
              style: TextStyle(fontSize: size * 0.86, height: 1.0),
              textAlign: TextAlign.center),
        ),
      );
    }
    return Image.network(
      icon,
      width: size,
      height: size,
      // 🛡 图标已按 1:1 校验过（后台拒非方图），所以直接 contain 不会变形。
      fit: BoxFit.contain,
      // 🛡 加载中与失败都**收缩为零**，不占位、不显示碎图（见类注释）。
      loadingBuilder: (context, child, progress) =>
          progress == null ? child : const SizedBox.shrink(),
      errorBuilder: (context, error, stack) => const SizedBox.shrink(),
    );
  }
}
