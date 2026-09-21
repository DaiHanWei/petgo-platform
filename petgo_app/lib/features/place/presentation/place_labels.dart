import '../../../l10n/app_localizations.dart';
import '../domain/place_summary.dart';

/// 场所枚举 → 界面文案（V1.3.0 batch-b1 Story 1.1）。
///
/// 🔴 **中文只作内部对照，不进任何界面**（架构 §4.3）：这里的每个字都来自 `.arb`，
/// 界面按设备语言渲染（en / id 双表）。
///
/// ⚠️ 两个 switch 都是**穷举的**（没有 default 分支）：后端加了新类型 / 新标签而这里没跟上时，
/// **Dart 编译期就会报错**，而不是在界面上显示一个空白标签。别为了图省事加 default。
extension PlaceTypeLabel on PlaceType {
  String label(AppLocalizations l10n) => switch (this) {
        PlaceType.cafe => l10n.placeTypeCafe,
        PlaceType.restaurant => l10n.placeTypeRestaurant,
        PlaceType.park => l10n.placeTypePark,
        PlaceType.mall => l10n.placeTypeMall,
        PlaceType.hotel => l10n.placeTypeHotel,
        PlaceType.petService => l10n.placeTypePetService,
        PlaceType.other => l10n.placeTypeOther,
      };
}

extension PlaceTagLabel on PlaceTag {
  String label(AppLocalizations l10n) => switch (this) {
        PlaceTag.petsAllowedInside => l10n.placeTagPetsAllowedInside,
        PlaceTag.outdoorSeating => l10n.placeTagOutdoorSeating,
        PlaceTag.petMenu => l10n.placeTagPetMenu,
        PlaceTag.petPlayArea => l10n.placeTagPetPlayArea,
        PlaceTag.leashRequired => l10n.placeTagLeashRequired,
        PlaceTag.largeDogFriendly => l10n.placeTagLargeDogFriendly,
      };
}
