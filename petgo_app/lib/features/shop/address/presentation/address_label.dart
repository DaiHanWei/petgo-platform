/// 地址标签的显示映射（v1.4.2 shop Story 9.4 AC3.6，§6 H3「线上写死在代码里」）。
///
/// 🔴 **提交给后端的值保持印尼语字面量** `Rumah | Kantor | Lainnya`（存量地址都是这三个，后端不做映射）；
/// 这里只把它们映射成当前语言的显示文案，其他未知值原样显示。
library;

import '../../../../l10n/app_localizations.dart';

/// 可选标签的提交值（顺序即胶囊顺序）。
const kAddressLabelValues = ['Rumah', 'Kantor', 'Lainnya'];

String addressDisplayLabel(AppLocalizations l10n, String raw) => switch (raw) {
      'Rumah' => l10n.addressLabelHome,
      'Kantor' => l10n.addressLabelOffice,
      'Lainnya' => l10n.addressLabelOther,
      _ => raw,
    };
