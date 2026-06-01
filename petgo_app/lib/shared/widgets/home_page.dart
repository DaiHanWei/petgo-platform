import 'package:flutter/material.dart';
import 'package:petgo/l10n/app_localizations.dart';

/// 空白首页占位（脚手架）。展示本地化 appTitle，用于验证 i18n 随设备语言切换。
/// 真正的首页 Feed / Tab Bar 外壳属后续 Story（1.2 起）。
class HomePage extends StatelessWidget {
  const HomePage({super.key});

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    return Scaffold(
      body: Center(
        child: Text(l10n.appTitle),
      ),
    );
  }
}
