import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:url_launcher/url_launcher.dart';

import '../../../l10n/app_localizations.dart';

/// 系统地图深链启动器（Story 4.5 · F3）。注入式以便测试替身。
/// 返回是否成功调起（失败由调用方降级提示，不崩溃）。
typedef MapsLauncher = Future<bool> Function(String query);

Future<bool> _launchMaps(String query) async {
  // 跨平台通用地图深链（iOS/Android 均可解析；交系统选择地图 app）。
  final uri = Uri.parse(
      'https://www.google.com/maps/search/?api=1&query=${Uri.encodeComponent(query)}');
  try {
    return await launchUrl(uri, mode: LaunchMode.externalApplication);
  } catch (_) {
    return false;
  }
}

final Provider<MapsLauncher> triageMapsLauncherProvider =
    Provider<MapsLauncher>((ref) => _launchMaps);

/// 「去导航」统一流程（Story 4.5）：系统确认 dialog → 调系统地图搜「宠物医院/Klinik Hewan」；
/// 失败降级提示，不崩溃。红色 overlay 与关闭后保留的红色摘要复用此流程（唯一就医出口）。
Future<void> confirmAndNavigate(BuildContext context, WidgetRef ref) async {
  final l10n = AppLocalizations.of(context);
  final ok = await showDialog<bool>(
    context: context,
    builder: (ctx) => AlertDialog(
      content: Text(l10n.triageRedNavConfirmTitle),
      actions: <Widget>[
        TextButton(onPressed: () => Navigator.pop(ctx, false), child: Text(l10n.commonCancel)),
        FilledButton(
          key: const ValueKey('triageRedNavConfirm'),
          onPressed: () => Navigator.pop(ctx, true),
          child: Text(l10n.triageRedNavConfirmOpen),
        ),
      ],
    ),
  );
  if (ok != true || !context.mounted) return;
  final launched = await ref.read(triageMapsLauncherProvider)(l10n.triageMapSearchQuery);
  if (!launched && context.mounted) {
    ScaffoldMessenger.of(context)
      ..clearSnackBars()
      ..showSnackBar(SnackBar(content: Text(l10n.triageRedMapsUnavailable)));
  }
}
