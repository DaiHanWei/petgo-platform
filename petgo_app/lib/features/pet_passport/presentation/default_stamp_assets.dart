import '../../place/domain/place_summary.dart';

/// 场所类型 → 包内默认章（V1.3.2 Story 1.2 · AD-5）。
///
/// 🔴 **穷举 switch、没有 default 分支**：后端加了新类型而这里没跟上时编译期就报错。
///
/// 素材暂不入库（决策日志 D-21 / 云端规则）：这里**只按约定路径引用**，
/// 渲染方（`PlaceStampView`）用 `Image.asset(errorBuilder: …)` —— 文件缺失时回落代码绘制的占位章。
/// 素材到货后只往 `assets/place_stamp/` 放同名文件即生效，不改代码。命名规范见该目录 README。
String? defaultStampAssetFor(PlaceType? type) => switch (type) {
      PlaceType.cafe => 'assets/place_stamp/cafe.png',
      PlaceType.restaurant => 'assets/place_stamp/restaurant.png',
      PlaceType.park => 'assets/place_stamp/park.png',
      PlaceType.mall => 'assets/place_stamp/mall.png',
      PlaceType.hotel => 'assets/place_stamp/hotel.png',
      PlaceType.petService => 'assets/place_stamp/pet_service.png',
      PlaceType.other => 'assets/place_stamp/other.png',
      null => null,
    };
