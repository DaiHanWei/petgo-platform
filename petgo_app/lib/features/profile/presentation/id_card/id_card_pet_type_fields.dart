import 'package:flutter/material.dart';

import '../../../../core/theme/colors.dart';
import '../../../../l10n/app_localizations.dart';
import '../../domain/pet_breeds.dart';
import 'id_card_ui_style.dart';

/// 身份证建卡表单专用的物种 / 品种下拉（V1.3.4 ktp Story 1.7，AD-9 / FR-142D.3，UI 稿 D7）。
///
/// 与档案页共享的 `SpeciesField` / `BreedField`（弹底部抽屉）**无关、不复用**——那两个组件零改动（NFR5）。
/// 选项以**浮层**展开在输入框正下方（UI 稿 D7 `.dd{position:absolute}`：覆盖下方内容、不推挤布局；
/// [OverlayPortal] + [CompositedTransformFollower]，随抽屉滚动跟随），当前项打勾；
/// 「同一时刻最多一个展开」由父级用 [expanded] / [onToggle] 统一管理。

const double _kRowHeight = 44;

// 稿 `.in`：圆角 12 · 1.5 描边（line / 展开 mint / `.bad` 红）。
BoxDecoration _boxDecoration({bool error = false, bool focused = false, bool disabled = false}) => BoxDecoration(
      color: disabled ? AppColors.line2 : AppColors.surface,
      borderRadius: BorderRadius.circular(kIdCardInputRadius),
      border: Border.all(
        color: error ? AppColors.danger : (focused ? AppColors.mint : AppColors.line),
        width: 1.5,
      ),
    );

// 稿 `.dd`：白底 · 圆角 12 · 内边距 4 · 双层阴影，无描边。
Widget _menu(List<Widget> rows, {double? maxHeight, Key? key}) => Container(
      key: key,
      margin: const EdgeInsets.only(top: 4),
      padding: const EdgeInsets.all(4),
      decoration: BoxDecoration(
        color: AppColors.surface,
        borderRadius: BorderRadius.circular(12),
        boxShadow: const [
          BoxShadow(color: Color(0x142E2A45), blurRadius: 2, offset: Offset(0, 1)),
          BoxShadow(color: Color(0x242E2A45), blurRadius: 24, offset: Offset(0, 8)),
        ],
      ),
      child: ConstrainedBox(
        constraints: BoxConstraints(maxHeight: maxHeight ?? double.infinity),
        // 透明 Material：选中行用 Ink 画 mintTint 底，Ink 画在最近的 Material 上——
        // 没有这层会画到外层、被本 Container 的白底盖住（稿 `.dd-i.on` 浅紫底不显示）
        child: Material(
          type: MaterialType.transparency,
          child: ListView(shrinkWrap: true, padding: EdgeInsets.zero, children: rows),
        ),
      ),
    );

/// 把下拉菜单作为浮层挂在输入框正下方（不占布局高度，覆盖下方字段）。
/// 浮层常驻（[OverlayPortalController.show] 在挂载前调用），[menu] 为 null 时浮层为空。
class _DropdownAnchor extends StatefulWidget {
  const _DropdownAnchor({required this.field, required this.menu});

  final Widget field;
  final Widget? menu;

  @override
  State<_DropdownAnchor> createState() => _DropdownAnchorState();
}

class _DropdownAnchorState extends State<_DropdownAnchor> {
  final _link = LayerLink();
  final _portal = OverlayPortalController()..show();

  @override
  Widget build(BuildContext context) => LayoutBuilder(
        builder: (context, constraints) => OverlayPortal(
          controller: _portal,
          overlayChildBuilder: (_) {
            final menu = widget.menu;
            if (menu == null) return const SizedBox.shrink();
            return CompositedTransformFollower(
              link: _link,
              showWhenUnlinked: false,
              targetAnchor: Alignment.bottomLeft,
              child: Align(
                alignment: Alignment.topLeft,
                child: SizedBox(
                  width: constraints.maxWidth,
                  child: Material(type: MaterialType.transparency, child: menu),
                ),
              ),
            );
          },
          child: CompositedTransformTarget(link: _link, child: widget.field),
        ),
      );
}

Widget _option({required Key key, required String label, required bool selected, required VoidCallback onTap}) =>
    InkWell(
      key: key,
      onTap: onTap,
      borderRadius: BorderRadius.circular(8),
      // 稿 `.dd-i` / `.dd-i.on`：行高 44 · 圆角 8 · 选中 mintTint 底 + mint 字 w600。
      child: Ink(
        height: _kRowHeight,
        decoration: BoxDecoration(
          color: selected ? AppColors.mintTint : null,
          borderRadius: BorderRadius.circular(8),
        ),
        child: Padding(
          padding: const EdgeInsets.symmetric(horizontal: 12),
          child: Row(
            children: [
              Expanded(
                child: Text(label,
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                    style: TextStyle(
                        fontSize: 14,
                        color: selected ? AppColors.mint : AppColors.ink,
                        fontWeight: selected ? FontWeight.w600 : FontWeight.w400)),
              ),
              if (selected) const Icon(Icons.check_rounded, size: 18, color: AppColors.mint),
            ],
          ),
        ),
      ),
    );

/// 物种下拉：`Kucing` / `Anjing` / `Lainnya`（wire CAT / DOG / OTHER）。
class IdCardSpeciesDropdown extends StatelessWidget {
  const IdCardSpeciesDropdown({
    super.key,
    required this.petType,
    required this.expanded,
    required this.onToggle,
    required this.onSelected,
    this.error = false,
  });

  final String? petType;
  final bool expanded;
  final VoidCallback onToggle;
  final ValueChanged<String> onSelected;

  /// 必填未选：描边变红（UI 稿 D6 `bad` 态）。
  final bool error;

  static List<(String, String)> options(AppLocalizations l10n) => [
        ('CAT', l10n.petTypeCat),
        ('DOG', l10n.petTypeDog),
        ('OTHER', l10n.petTypeOther),
      ];

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final opts = options(l10n);
    final current = opts.where((o) => o.$1 == petType).map((o) => o.$2).firstOrNull;
    return _DropdownAnchor(
      field: InkWell(
        key: const ValueKey('idCardSpeciesField'),
        onTap: onToggle,
        borderRadius: BorderRadius.circular(kIdCardInputRadius),
        child: Container(
          height: 48,
          padding: const EdgeInsets.symmetric(horizontal: 14),
          decoration: _boxDecoration(error: error && !expanded, focused: expanded),
          child: Row(
            children: [
              Expanded(
                child: Text(current ?? l10n.petProfileTypePick,
                    style: TextStyle(color: current == null ? AppColors.muted : AppColors.ink)),
              ),
              Icon(expanded ? Icons.keyboard_arrow_up_rounded : Icons.keyboard_arrow_down_rounded,
                  color: expanded ? AppColors.mint : AppColors.muted),
            ],
          ),
        ),
      ),
      menu: expanded
          ? _menu([
              for (final (wire, label) in opts)
                _option(
                  key: ValueKey('idCardSpeciesOption_$wire'),
                  label: label,
                  selected: wire == petType,
                  onTap: () => onSelected(wire),
                ),
            ])
          : null,
    );
  }
}

/// 品种下拉：当前物种的品种清单（约 5 项高，超出在菜单内滚动）+ 末项「Lainnya」原位展开输入框。
/// 未选物种 → 禁用并提示「Pilih jenis dulu」；物种 OTHER（无清单）→ 直接是输入框。
/// 预填品种不在清单内（档案自定义）→「Lainnya」打勾、输入框预填该值。
class IdCardBreedDropdown extends StatefulWidget {
  const IdCardBreedDropdown({
    super.key,
    required this.petType,
    required this.controller,
    required this.expanded,
    required this.onToggle,
    required this.onChanged,
    this.onInputTap,
  });

  final String? petType;
  final TextEditingController controller;

  /// 手填输入框获得点击时回调（父级借此收起其他下拉，保证同时只有一个展开）。
  final VoidCallback? onInputTap;
  final bool expanded;
  final VoidCallback onToggle;
  final VoidCallback onChanged;

  @override
  State<IdCardBreedDropdown> createState() => _IdCardBreedDropdownState();
}

class _IdCardBreedDropdownState extends State<IdCardBreedDropdown> {
  /// 「Lainnya」手填模式。
  late bool _custom = _isCustomValue();

  bool _isCustomValue() {
    final v = widget.controller.text.trim();
    return v.isNotEmpty && !breedsForPetType(widget.petType).contains(v);
  }

  @override
  void didUpdateWidget(IdCardBreedDropdown old) {
    super.didUpdateWidget(old);
    // 改物种（父级同时清空品种）→ 退出手填模式。
    if (old.petType != widget.petType) _custom = _isCustomValue();
  }

  void _select(String breed) {
    widget.controller.text = breed;
    setState(() => _custom = false);
    widget.onChanged();
    widget.onToggle(); // 选中即收起
  }

  void _chooseOther() {
    if (!_custom) widget.controller.clear();
    setState(() => _custom = true);
    widget.onChanged();
    widget.onToggle();
  }

  Widget _customInput(AppLocalizations l10n, {Key key = const ValueKey('idCardBreedCustomInput')}) => TextField(
        key: key,
        controller: widget.controller,
        maxLength: 60,
        onTap: widget.onInputTap,
        onChanged: (_) => widget.onChanged(),
        decoration: idCardInputDecoration(hintText: l10n.petProfileBreedHint),
      );

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final petType = widget.petType;
    if (petType == null) {
      return Container(
        key: const ValueKey('idCardBreedField'),
        height: 48,
        alignment: Alignment.centerLeft,
        padding: const EdgeInsets.symmetric(horizontal: 14),
        decoration: _boxDecoration(disabled: true),
        child: Text(l10n.petProfileBreedNeedType, style: const TextStyle(color: AppColors.muted)),
      );
    }
    final breeds = breedsForPetType(petType);
    if (breeds.isEmpty) {
      // OTHER：无清单，直接手填（与档案 BreedField 对 OTHER 的语义一致）。
      return _customInput(l10n, key: const ValueKey('idCardBreedField'));
    }
    final value = widget.controller.text.trim();
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        _DropdownAnchor(
          field: InkWell(
            key: const ValueKey('idCardBreedField'),
            onTap: widget.onToggle,
            borderRadius: BorderRadius.circular(kIdCardInputRadius),
            child: Container(
              height: 48,
              padding: const EdgeInsets.symmetric(horizontal: 14),
              decoration: _boxDecoration(focused: widget.expanded),
              child: Row(
                children: [
                  Expanded(
                    child: Text(
                      value.isEmpty ? l10n.petProfileBreedHint : value,
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                      style: TextStyle(color: value.isEmpty ? AppColors.muted : AppColors.ink),
                    ),
                  ),
                  Icon(widget.expanded ? Icons.keyboard_arrow_up_rounded : Icons.keyboard_arrow_down_rounded,
                      color: widget.expanded ? AppColors.mint : AppColors.muted),
                ],
              ),
            ),
          ),
          menu: widget.expanded
              ? _menu(
                  [
                    for (final b in breeds)
                      _option(
                        key: ValueKey('idCardBreedOption_$b'),
                        label: b,
                        selected: !_custom && b == value,
                        onTap: () => _select(b),
                      ),
                    _option(
                      key: const ValueKey('idCardBreedOption_OTHER'),
                      label: l10n.petProfileBreedOther,
                      selected: _custom,
                      onTap: _chooseOther,
                    ),
                  ],
                  maxHeight: _kRowHeight * 5,
                  key: const ValueKey('idCardBreedMenu'),
                )
              : null,
        ),
        if (_custom) ...[
          const SizedBox(height: 6),
          _customInput(l10n),
        ],
      ],
    );
  }
}
