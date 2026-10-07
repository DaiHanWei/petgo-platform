import 'package:flutter/material.dart';

import '../../../../core/theme/colors.dart';

/// 结果页 ⋯ 菜单的一项（V1.3.2 Story 2.4 · AC6）。Epic 4 往同一菜单追加「Bagikan」「Pamer di postingan」。
typedef TsMenuItem = ({Key key, IconData icon, String label, VoidCallback onTap});

/// 底部操作菜单（列表项样式）。点某项先关菜单再执行该项回调。
Future<void> showTsResultMenu(BuildContext context, List<TsMenuItem> items) {
  return showModalBottomSheet<void>(
    context: context,
    backgroundColor: Colors.transparent,
    // 根用 Material（而不是带底色的 Container）：ListTile 的水波纹画在最近的 Material 上，被底色盖住就看不见。
    builder: (ctx) => Material(
      color: AppColors.surface,
      shape: const RoundedRectangleBorder(borderRadius: BorderRadius.vertical(top: Radius.circular(24))),
      child: Padding(
        padding: const EdgeInsets.fromLTRB(8, 12, 8, 16),
        child: SafeArea(
          top: false,
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              Container(
                width: 36,
                height: 4,
                margin: const EdgeInsets.only(bottom: 10),
                decoration: BoxDecoration(color: AppColors.line, borderRadius: BorderRadius.circular(99)),
              ),
              for (final item in items)
                ListTile(
                  key: item.key,
                  minTileHeight: 52,
                  leading: Icon(item.icon, color: AppColors.ink2),
                  title: Text(item.label, style: const TextStyle(fontSize: 15, fontWeight: FontWeight.w600)),
                  onTap: () {
                    Navigator.of(ctx).pop();
                    item.onTap();
                  },
                ),
            ],
          ),
        ),
      ),
    ),
  );
}
