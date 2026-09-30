import 'package:flutter/material.dart';

import '../../../../core/theme/colors.dart';
import '../../../pet_passport/presentation/default_stamp_assets.dart';
import '../../domain/place_summary.dart';

/// 场所章面（V1.3.2 batch-a Story 1.1 新建；Story 1.2 护照格子 / 1.3 章详情 / 1.4 专属章复用）。
///
/// 回落链（AD-5 / D-10 / D-12）：
/// 1. [imageUrl] 非空 → 场所专属章（Story 1.4 接入）；
/// 2. 否则按 [placeType] 的包内默认章（Story 1.2 `defaultStampAssetFor`，素材未到货时文件缺失 → 走第 3 级）；
/// 3. 都没有 → **代码绘制的占位章**：浅色底 + 圆形描边 + 类型图标。
///
/// 🔴 **原色展示**：不着色、不做圆形裁切（D-10：章不一定是圆的）。本文件不得出现
/// `ClipOval` / `ColorFiltered` / `colorBlendMode`（Story 1.4 有源码扫描测试）。
/// Story 1.1 实现第 3 级；Story 1.2 接第 2 级（素材缺失时 `errorBuilder` 回落第 3 级）；
/// Story 1.4 接第 1 级：专属章网络图加载中 / 失败都回落第 2 → 3 级。
class PlaceStampView extends StatelessWidget {
  const PlaceStampView({
    super.key,
    required this.placeType,
    this.imageUrl,
    this.size = 96,
  });

  /// null = 客户端不认识的类型 → 通用图标。
  final PlaceType? placeType;

  /// 场所专属章 URL（Story 1.4 起有值）。
  final String? imageUrl;

  /// 边长（章面按正方形排版，与 512×512 专属章同比例）。
  final double size;

  @override
  Widget build(BuildContext context) {
    final fallback = _defaultOrPlaceholder();
    final url = imageUrl;
    return SizedBox.square(
      dimension: size,
      child: (url == null || url.isEmpty)
          ? fallback
          : Image.network(
              url,
              width: size,
              height: size,
              // 🔴 原色、按原图比例完整展示：不着色、不圆形裁切（D-10）。
              fit: BoxFit.contain,
              frameBuilder: (context, child, frame, sync) =>
                  (frame == null && !sync) ? fallback : child,
              errorBuilder: (context, error, stack) => fallback,
            ),
    );
  }

  /// 第 2 级默认章 → 第 3 级占位章。
  Widget _defaultOrPlaceholder() {
    final placeholder = PlaceStampPlaceholder(placeType: placeType, size: size);
    final asset = defaultStampAssetFor(placeType);
    if (asset == null) return placeholder;
    return Image.asset(
      asset,
      width: size,
      height: size,
      fit: BoxFit.contain,
      // 加载中与缺文件都显示占位章：素材未到货时这里就是常态（D-21），不是错误。
      frameBuilder: (context, child, frame, sync) => (frame == null && !sync) ? placeholder : child,
      errorBuilder: (context, error, stack) => placeholder,
    );
  }
}

/// 代码绘制的占位章（素材缺失时的最后一级回落）。
///
/// 浅紫底 + 双圈描边 + 类型图标；尺寸由外层给定，比例 1:1 与最终素材一致。
class PlaceStampPlaceholder extends StatelessWidget {
  const PlaceStampPlaceholder({super.key, required this.placeType, required this.size});

  final PlaceType? placeType;
  final double size;

  @override
  Widget build(BuildContext context) {
    return Container(
      key: const ValueKey('placeStampPlaceholder'),
      width: size,
      height: size,
      decoration: BoxDecoration(
        color: AppColors.mintTint,
        shape: BoxShape.circle,
        border: Border.all(color: AppColors.mint, width: size * 0.035),
      ),
      padding: EdgeInsets.all(size * 0.08),
      child: Container(
        decoration: BoxDecoration(
          shape: BoxShape.circle,
          border: Border.all(color: AppColors.lineViolet, width: size * 0.02),
        ),
        alignment: Alignment.center,
        child: Icon(iconFor(placeType), size: size * 0.4, color: AppColors.mint),
      ),
    );
  }

  /// 类型 → 图标。穷举 switch（后端加类型时编译期报错）。
  static IconData iconFor(PlaceType? type) => switch (type) {
        PlaceType.cafe => Icons.local_cafe_rounded,
        PlaceType.restaurant => Icons.restaurant_rounded,
        PlaceType.park => Icons.park_rounded,
        PlaceType.mall => Icons.local_mall_rounded,
        PlaceType.hotel => Icons.hotel_rounded,
        PlaceType.petService => Icons.pets_rounded,
        PlaceType.other => Icons.place_rounded,
        null => Icons.place_rounded,
      };
}
