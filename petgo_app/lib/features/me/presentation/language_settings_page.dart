import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/l10n/locale_controller.dart';
import '../../../core/theme/colors.dart';
import '../../../l10n/app_localizations.dart';

/// 语言设置页（Story 7.2，FR-27）。id / en / 跟随系统 单选，切换即时生效 + 持久化（无需重启）。
/// UGC 不受语言设置影响（按原文显示）。
class LanguageSettingsPage extends ConsumerWidget {
  const LanguageSettingsPage({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final l10n = AppLocalizations.of(context);
    final current = ref.watch(localeControllerProvider)?.languageCode; // null=跟随系统
    final controller = ref.read(localeControllerProvider.notifier);

    return Scaffold(
      backgroundColor: AppColors.base,
      appBar: AppBar(title: Text(l10n.languageSettingsTitle)),
      body: ListView(
        children: [
          _option(const ValueKey('langFollowSystem'), l10n.languageFollowSystem, null, current,
              () => controller.setLanguage(null)),
          _option(const ValueKey('langEn'), l10n.languageEnglish, 'en', current,
              () => controller.setLanguage('en')),
          _option(const ValueKey('langId'), l10n.languageIndonesian, 'id', current,
              () => controller.setLanguage('id')),
        ],
      ),
    );
  }

  Widget _option(Key key, String label, String? value, String? current, VoidCallback onTap) {
    final selected = value == current;
    return ListTile(
      key: key,
      title: Text(label),
      trailing: selected ? const Icon(Icons.check, color: AppColors.accentConsult) : null,
      onTap: onTap,
    );
  }
}
