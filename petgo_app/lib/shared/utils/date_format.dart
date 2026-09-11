import 'package:flutter/widgets.dart';
import 'package:intl/intl.dart';

import '../../l10n/app_localizations.dart';

/// 本地化日期格式化（V1：en / id）。
///
/// 月份名等走 `intl` 的 locale 符号，避免源里硬编码月份数组（i18n 纪律：用户可见字符串不硬编码）。
/// locale 数据在 `main()` 启动期由 `initializeDateFormatting()` 注入（id 非默认 locale，必须初始化）。
/// 模式用显式骨架（`d MMM yyyy` 等），跨 locale 顺序一致、与原型一致。
String _ln(BuildContext context) => Localizations.localeOf(context).languageCode;

/// "1 Mei 2022" / "1 May 2022"。生日等完整日期。
String formatBirthday(BuildContext context, DateTime d) =>
    DateFormat('d MMMM yyyy', _ln(context)).format(d);

/// "Juni 2026" / "June 2026"。时间线月份分组标题。
String formatMonthYear(BuildContext context, DateTime d) =>
    DateFormat('MMMM yyyy', _ln(context)).format(d);

/// "15 Jun 2025"。缩写月份的完整日期。
String formatDayMonthYear(BuildContext context, DateTime d) =>
    DateFormat('d MMM yyyy', _ln(context)).format(d);

/// "15 Jun"。缩写月份的日 + 月。
String formatDayMonth(BuildContext context, DateTime d) =>
    DateFormat('d MMM', _ln(context)).format(d);

/// "S" / "M"。星期几的**最窄**写法（日历表头一行 7 个字母）。
String formatWeekdayNarrow(BuildContext context, DateTime d) =>
    DateFormat('EEEEE', _ln(context)).format(d);

/// "Jun"。仅缩写月份（日期列）。
String formatMonthAbbr(BuildContext context, DateTime d) =>
    DateFormat('MMM', _ln(context)).format(d);

/// "30 Jun 19:42"。缩写月份的日 + 月 + 时分（订单卡副行）。
String formatDayMonthTime(BuildContext context, DateTime d) =>
    DateFormat('d MMM HH:mm', _ln(context)).format(d);

/// "28 Jun 2026, 22:09"。完整日期 + 时分（订单详情行）。
String formatDayMonthYearTime(BuildContext context, DateTime d) =>
    DateFormat('d MMM yyyy, HH:mm', _ln(context)).format(d);

/// 发布时间的**统一显示规则**（V1.3.0 Story 2.2 定，Story 2.5 起评论区共用）。
///
/// 7 天以内走相对时间（刚刚 / N 分钟前 / N 小时前 / N 天前），**超过 7 天改绝对日期**
/// （如「15 Jun 2025」，走 [formatDayMonthYear]，已按 locale 本地化）。
///
/// 🔴 **详情页与评论区必须用同一个函数**（Story 2.5 · AC1）：两处显示的是同一类东西，
/// 各写一份迟早分叉 —— 一边「173 天前」一边「15 Jun 2025」，用户会以为是两种不同的时间。
/// 「173 天前」这种数字读者根本换算不过来，而详情页与评论区都常有很久以前的内容。
String formatPublishTime(BuildContext context, AppLocalizations l10n, DateTime t) {
  final d = DateTime.now().difference(t);
  if (d.inMinutes < 1) return l10n.timeJustNow;
  if (d.inHours < 1) return l10n.timeMinutesAgo(d.inMinutes);
  if (d.inDays < 1) return l10n.timeHoursAgo(d.inHours);
  if (d.inDays > 7) return formatDayMonthYear(context, t);
  return l10n.timeDaysAgo(d.inDays);
}
