import 'package:flutter/widgets.dart';
import 'package:intl/intl.dart';

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

/// "Jun"。仅缩写月份（日期列）。
String formatMonthAbbr(BuildContext context, DateTime d) =>
    DateFormat('MMM', _ln(context)).format(d);

/// "30 Jun 19:42"。缩写月份的日 + 月 + 时分（订单卡副行）。
String formatDayMonthTime(BuildContext context, DateTime d) =>
    DateFormat('d MMM HH:mm', _ln(context)).format(d);

/// "28 Jun 2026, 22:09"。完整日期 + 时分（订单详情行）。
String formatDayMonthYearTime(BuildContext context, DateTime d) =>
    DateFormat('d MMM yyyy, HH:mm', _ln(context)).format(d);
