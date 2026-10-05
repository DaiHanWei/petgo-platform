import 'package:flutter/widgets.dart';
import 'package:go_router/go_router.dart';

import '../../../../l10n/app_localizations.dart';
import '../../../../shared/widgets/app_toast.dart';
import '../../../place/presentation/place_detail_page.dart';
import '../../domain/timeline_item.dart';

/// Diary 打卡条目的点击语义（V1.3.2 Story 1.6 · AC4.3 / AC4.4）：时间线与某天详情共用，
/// 组件本身不持有跳转（`TimelineItemTile` 的既有约定）。
///
/// 场所 ACTIVE → 场所详情（`from=diary`）；UNAVAILABLE / 缺字段 → 只提示「Tempat tidak ditemukan」、不跳。
/// [report] 在两种情况下都先调（埋点 `item_type` 自动为 `PLACE_CHECKIN_BANNER`）。
VoidCallback placeCheckinTapFor(BuildContext context, TimelineItem item, {VoidCallback? report}) {
  return () {
    report?.call();
    final place = item.checkinPlace;
    if (place != null && place.available) {
      context.push(PlaceDetailPage.routeFor(place.token, from: kPlaceDetailFromDiary));
    } else {
      showAppToast(context, AppLocalizations.of(context).placeDeletedToast);
    }
  };
}
