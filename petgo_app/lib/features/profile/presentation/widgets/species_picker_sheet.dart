import 'dart:async';

import 'package:flutter/material.dart';

import '../../../../core/analytics/analytics.dart';
import '../../../../core/theme/colors.dart';
import '../../../../l10n/app_localizations.dart';
import '../../../../shared/widgets/keyboard_safe_area.dart';
import '../../domain/pet_species.dart';
import '../../domain/pet_species_display.dart';

/// 宠物类型 / 品种选择抽屉（V1.5.0 pet-species Story 3.2 · 架构 D-18 · UI A2 ~ A4、B1 ~ B6）。
///
/// 一个外壳两个用法：[showPetSpeciesPicker]（类型宫格 + 搜索）与 [showPetBreedPicker]（品种列表 + 搜索 + 「Pakai」手填）。
/// 🔴 建档 / 编辑 / 身份证建卡三处只用这一个组件；匹配只走 `pet_species.dart` 的函数。
/// 埋点事件名写字面量：`pet_type_selected` / `pet_breed_selected` / `pet_breed_search_no_result`。

/// 类型抽屉的结果。
class PetSpeciesPick {
  const PetSpeciesPick(this.code, {this.viaSearch = false});

  final PetSpeciesCode code;
  final bool viaSearch;
}

/// 品种抽屉的结果：[label] 是写进 `breed` 的值；[species] 是选中后应有的细分类型（「其他」组品种 → 该组物种）。
class BreedPick {
  const BreedPick(this.label, this.species, {this.isCustom = false, this.viaSearch = false});

  final String label;
  final PetSpeciesCode species;
  final bool isCustom;
  final bool viaSearch;
}

/// 手填值上限（同后端 `breed VARCHAR(60)`）。
const int kBreedCustomMaxLength = 60;

/// 无结果埋点的停顿判定（PRD §4：输入停 800ms 后结果仍为空才报）。
const Duration kBreedNoResultDebounce = Duration(milliseconds: 800);

/// 按字形截断（不把 emoji 等代理对切成半个）。
String _clip(String s, int max) {
  final c = s.characters;
  return c.length > max ? c.take(max).toString() : s;
}

Future<T?> _showSheet<T>(BuildContext context, Widget child) => showModalBottomSheet<T>(
      context: context,
      isScrollControlled: true,
      useSafeArea: true,
      backgroundColor: AppColors.surface,
      // 叠在另一个抽屉上弹出（身份证「Ubah info」）时同样有遮罩；返回键先关上层（路由栈自然行为）。
      barrierColor: Colors.black54,
      shape: const RoundedRectangleBorder(borderRadius: BorderRadius.vertical(top: Radius.circular(20))),
      builder: (_) => child,
    );

/// 类型抽屉（A2 ~ A4）。点外部 / 下拉关闭 → null。
Future<PetSpeciesPick?> showPetSpeciesPicker(BuildContext context,
        {PetSpeciesCode? current, required String scene}) =>
    _showSheet<PetSpeciesPick>(context, _SpeciesSheet(current: current, scene: scene));

/// 品种抽屉（B1 ~ B6）。点外部 / 下拉关闭 → null。
Future<BreedPick?> showPetBreedPicker(BuildContext context,
        {required PetSpeciesCode type, String? current, required String scene}) =>
    _showSheet<BreedPick>(context, _BreedSheet(type: type, current: current, scene: scene));

// ---------------------------------------------------------------------------------------------------------------
// 外壳：拖拽条 + 标题 + 常驻搜索框 + 可滚动内容区（+ 可选底部固定行）；键盘弹起整体上移。
// ---------------------------------------------------------------------------------------------------------------

class SpeciesPickerScaffold extends StatelessWidget {
  const SpeciesPickerScaffold({
    super.key,
    required this.title,
    required this.searchHint,
    required this.controller,
    required this.focusNode,
    required this.body,
    this.footer,
  });

  final String title;
  final String searchHint;
  final TextEditingController controller;
  final FocusNode focusNode;
  final Widget body;
  final Widget? footer;

  @override
  Widget build(BuildContext context) {
    final mq = MediaQuery.of(context);
    final maxH = mq.size.height * 0.9 - mq.viewInsets.bottom;
    // 键盘避让走共享件 KeyboardInset（petgo_app/CLAUDE.md 底部弹层标准）。
    return KeyboardInset(
      child: ConstrainedBox(
        constraints: BoxConstraints(maxHeight: maxH < 240 ? 240 : maxH),
        child: Padding(
          padding: const EdgeInsets.fromLTRB(20, 6, 20, 12),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              Center(
                child: Container(
                  width: 36,
                  height: 4,
                  decoration: BoxDecoration(color: AppColors.line, borderRadius: BorderRadius.circular(9999)),
                ),
              ),
              const SizedBox(height: 16),
              Text(title,
                  key: const ValueKey('speciesPickerTitle'),
                  style: const TextStyle(color: AppColors.ink, fontSize: 18, fontWeight: FontWeight.w700)),
              const SizedBox(height: 12),
              _SearchBox(controller: controller, focusNode: focusNode, hint: searchHint),
              const SizedBox(height: 10),
              Flexible(child: body),
              if (footer != null) ...[const SizedBox(height: 6), footer!],
            ],
          ),
        ),
      ),
    );
  }
}

class _SearchBox extends StatelessWidget {
  const _SearchBox({required this.controller, required this.focusNode, required this.hint});

  final TextEditingController controller;
  final FocusNode focusNode;
  final String hint;

  @override
  Widget build(BuildContext context) {
    return SizedBox(
      height: 44,
      child: ValueListenableBuilder<TextEditingValue>(
        valueListenable: controller,
        builder: (context, v, _) => TextField(
          key: const ValueKey('speciesPickerSearch'),
          controller: controller,
          focusNode: focusNode,
          textInputAction: TextInputAction.search,
          style: const TextStyle(fontSize: 14, color: AppColors.ink),
          decoration: InputDecoration(
            hintText: hint,
            hintStyle: const TextStyle(fontSize: 14, color: AppColors.muted),
            isDense: true,
            filled: true,
            fillColor: AppColors.mintTint2,
            contentPadding: const EdgeInsets.symmetric(vertical: 12),
            prefixIcon: const Icon(Icons.search_rounded, size: 20, color: AppColors.muted),
            suffixIcon: v.text.isEmpty
                ? null
                : IconButton(
                    key: const ValueKey('speciesPickerSearchClear'),
                    constraints: const BoxConstraints(minWidth: 44, minHeight: 44),
                    icon: const Icon(Icons.cancel, size: 18, color: AppColors.muted),
                    onPressed: controller.clear,
                  ),
            border: OutlineInputBorder(
                borderRadius: BorderRadius.circular(12), borderSide: const BorderSide(color: AppColors.line)),
            enabledBorder: OutlineInputBorder(
                borderRadius: BorderRadius.circular(12), borderSide: const BorderSide(color: AppColors.line)),
            focusedBorder: OutlineInputBorder(
                borderRadius: BorderRadius.circular(12), borderSide: const BorderSide(color: AppColors.mint)),
          ),
        ),
      ),
    );
  }
}

/// 列表行（选中态照身份证下拉 `_option`：浅紫底 + 紫字 + 勾）；命中段加粗。
class _OptionRow extends StatelessWidget {
  const _OptionRow({
    required super.key,
    required this.label,
    required this.query,
    required this.selected,
    required this.onTap,
    this.leading,
  });

  final String label;
  final String query;
  final bool selected;
  final VoidCallback onTap;
  final String? leading;

  @override
  Widget build(BuildContext context) {
    final base = TextStyle(
        fontSize: 14,
        color: selected ? AppColors.mint : AppColors.ink,
        fontWeight: selected ? FontWeight.w600 : FontWeight.w400);
    final ranges = highlightRanges(label, query);
    final spans = <TextSpan>[];
    var at = 0;
    for (final r in ranges) {
      if (r.start > at) spans.add(TextSpan(text: label.substring(at, r.start)));
      spans.add(TextSpan(text: label.substring(r.start, r.end), style: const TextStyle(fontWeight: FontWeight.w700)));
      at = r.end;
    }
    if (at < label.length) spans.add(TextSpan(text: label.substring(at)));
    return InkWell(
      onTap: onTap,
      borderRadius: BorderRadius.circular(8),
      child: Ink(
        height: 48,
        decoration: BoxDecoration(
          color: selected ? AppColors.mintTint : null,
          borderRadius: BorderRadius.circular(8),
        ),
        child: Padding(
          padding: const EdgeInsets.symmetric(horizontal: 12),
          child: Row(
            children: [
              if (leading != null) ...[
                Text(leading!, style: const TextStyle(fontSize: 18)),
                const SizedBox(width: 10),
              ],
              Expanded(
                child: Text.rich(TextSpan(style: base, children: spans),
                    maxLines: 1, overflow: TextOverflow.ellipsis),
              ),
              if (selected) const Icon(Icons.check_rounded, size: 18, color: AppColors.mint),
            ],
          ),
        ),
      ),
    );
  }
}

// ---------------------------------------------------------------------------------------------------------------
// 类型抽屉
// ---------------------------------------------------------------------------------------------------------------

class _SpeciesSheet extends StatefulWidget {
  const _SpeciesSheet({required this.current, required this.scene});

  final PetSpeciesCode? current;
  final String scene;

  @override
  State<_SpeciesSheet> createState() => _SpeciesSheetState();
}

class _SpeciesSheetState extends State<_SpeciesSheet> {
  final _controller = TextEditingController();
  final _focus = FocusNode();

  @override
  void initState() {
    super.initState();
    _controller.addListener(() => setState(() {}));
  }

  @override
  void dispose() {
    _controller.dispose();
    _focus.dispose();
    super.dispose();
  }

  void _pick(PetSpeciesCode code, {required bool viaSearch}) {
    Analytics.capture('pet_type_selected', {'species': code.wire, 'scene': widget.scene, 'via_search': viaSearch});
    Navigator.of(context).pop(PetSpeciesPick(code, viaSearch: viaSearch));
  }

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final q = _controller.text;
    final selected = widget.current?.pickerType;
    Widget body;
    if (normalizeQuery(q).isEmpty) {
      body = _grid(l10n, selected);
    } else {
      final hits = kSpeciesTypes
          .where((t) => speciesQueryMatches(q, [t.nameId, t.nameEn, petSpeciesView(species: t.code.wire).label(l10n)]))
          .toList();
      body = hits.isEmpty
          ? _empty(l10n, q)
          : ListView(
              key: const ValueKey('speciesPickerList'),
              shrinkWrap: true,
              children: [
                for (final t in hits)
                  _OptionRow(
                    key: ValueKey('speciesOption_${t.code.wire}'),
                    leading: t.emoji,
                    label: petSpeciesView(species: t.code.wire).label(l10n),
                    query: q,
                    selected: t.code == selected,
                    onTap: () => _pick(t.code, viaSearch: true),
                  ),
              ],
            );
    }
    return SpeciesPickerScaffold(
      title: l10n.petProfileTypePick,
      searchHint: l10n.petSpeciesSearchHint,
      controller: _controller,
      focusNode: _focus,
      body: body,
    );
  }

  /// A2：3 列 × 5 行宫格（格高 80，emoji 30 + 名 13），「Lainnya」最后；一屏放下不滚动。
  Widget _grid(AppLocalizations l10n, PetSpeciesCode? selected) {
    return GridView.count(
      key: const ValueKey('speciesPickerGrid'),
      crossAxisCount: 3,
      shrinkWrap: true,
      mainAxisSpacing: 8,
      crossAxisSpacing: 8,
      mainAxisExtent: 80,
      physics: const NeverScrollableScrollPhysics(),
      children: [
        for (final t in kSpeciesTypes)
          _cell(t, petSpeciesView(species: t.code.wire).label(l10n), t.code == selected),
      ],
    );
  }

  Widget _cell(SpeciesType t, String label, bool on) => InkWell(
        key: ValueKey('speciesCell_${t.code.wire}'),
        onTap: () => _pick(t.code, viaSearch: false),
        borderRadius: BorderRadius.circular(12),
        child: Ink(
          decoration: BoxDecoration(
            color: on ? AppColors.mintTint : AppColors.surface,
            borderRadius: BorderRadius.circular(12),
            border: Border.all(color: on ? AppColors.mint : AppColors.line, width: on ? 1.5 : 1),
          ),
          child: Stack(
            children: [
              Center(
                child: Column(
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    Text(t.emoji, style: const TextStyle(fontSize: 30, height: 1.1)),
                    const SizedBox(height: 4),
                    Text(label,
                        maxLines: 1,
                        overflow: TextOverflow.ellipsis,
                        style: TextStyle(
                            fontSize: 13,
                            color: on ? AppColors.mint : AppColors.ink,
                            fontWeight: on ? FontWeight.w600 : FontWeight.w400)),
                  ],
                ),
              ),
              if (on)
                const Positioned(
                    top: 6, right: 6, child: Icon(Icons.check_circle, size: 16, color: AppColors.mint)),
            ],
          ),
        ),
      );

  /// A4：类型无结果——浅紫圆底 search_off + 标题 + 说明 + 一行「🐾 Lainnya」（类型不提供手填）。
  Widget _empty(AppLocalizations l10n, String q) {
    return SingleChildScrollView(
      key: const ValueKey('speciesPickerEmpty'),
      child: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          const SizedBox(height: 12),
          Container(
            width: 56,
            height: 56,
            decoration: const BoxDecoration(color: AppColors.mintTint, shape: BoxShape.circle),
            child: const Icon(Icons.search_off_rounded, color: AppColors.mint, size: 28),
          ),
          const SizedBox(height: 10),
          Text(l10n.petSpeciesNoResultTitle(q.trim()),
              textAlign: TextAlign.center,
              style: const TextStyle(fontSize: 15, fontWeight: FontWeight.w600, color: AppColors.ink)),
          const SizedBox(height: 4),
          Text(l10n.petSpeciesNoResultBody,
              textAlign: TextAlign.center, style: const TextStyle(fontSize: 13, color: AppColors.ink2)),
          const SizedBox(height: 12),
          _OptionRow(
            key: const ValueKey('speciesOption_OTHER'),
            leading: '🐾',
            label: l10n.petTypeOther,
            query: '',
            selected: false,
            onTap: () => _pick(PetSpeciesCode.other, viaSearch: true),
          ),
        ],
      ),
    );
  }
}

// ---------------------------------------------------------------------------------------------------------------
// 品种抽屉
// ---------------------------------------------------------------------------------------------------------------

class _BreedSheet extends StatefulWidget {
  const _BreedSheet({required this.type, required this.current, required this.scene});

  final PetSpeciesCode type;
  final String? current;
  final String scene;

  @override
  State<_BreedSheet> createState() => _BreedSheetState();
}

class _BreedSheetState extends State<_BreedSheet> {
  late final TextEditingController _controller;
  final _focus = FocusNode();
  Timer? _noResultTimer;
  final Set<String> _reportedNoResult = <String>{};

  /// 已选的是手填值（B4）：打开时预填搜索框、不弹键盘、「Pakai」行打勾。
  late final bool _currentIsCustom;

  @override
  void initState() {
    super.initState();
    final cur = widget.current?.trim() ?? '';
    _currentIsCustom = cur.isNotEmpty && !isPresetBreed(widget.type, cur);
    _controller = TextEditingController(text: _currentIsCustom ? cur : '');
    _controller.addListener(_onQuery);
  }

  @override
  void dispose() {
    _noResultTimer?.cancel();
    _controller.dispose();
    _focus.dispose();
    super.dispose();
  }

  bool get _isOtherPicker => widget.type.pickerType == PetSpeciesCode.other;

  void _onQuery() {
    setState(() {});
    _noResultTimer?.cancel();
    final q = _controller.text.trim();
    if (q.isEmpty) return;
    _noResultTimer = Timer(kBreedNoResultDebounce, () {
      if (!mounted || _controller.text.trim() != q || _hasResults(q)) return;
      final query = _clip(q, kBreedCustomMaxLength);
      if (!_reportedNoResult.add(normalizeQuery(query))) return;
      Analytics.capture('pet_breed_search_no_result', {'species': widget.type.wire, 'query': query});
    });
  }

  bool _hasResults(String q) => _isOtherPicker
      ? _otherGroupsFor(q).isNotEmpty
      : breedsFor(widget.type).any((b) => speciesQueryMatches(q, breedCandidates(b)));

  /// 「其他」：命中组标题 → 整组；否则组内命中的品种（+ 组末项按「<组> lainnya」文字匹配）。
  List<(SpeciesGroup, List<BreedOption>, bool)> _otherGroupsFor(String q) {
    final out = <(SpeciesGroup, List<BreedOption>, bool)>[];
    for (final g in kOtherSpeciesGroups) {
      if (speciesQueryMatches(q, [g.titleId, g.titleEn])) {
        out.add((g, g.breeds, true));
        continue;
      }
      final hits = g.breeds.where((b) => speciesQueryMatches(q, breedCandidates(b))).toList();
      final lainnyaHit = speciesQueryMatches(q, ['${g.titleId} lainnya']);
      if (hits.isNotEmpty || lainnyaHit) out.add((g, hits, lainnyaHit));
    }
    return out;
  }

  void _pick(BreedPick pick) {
    Analytics.capture('pet_breed_selected', {
      'species': pick.species.wire,
      'scene': widget.scene,
      'is_custom': pick.isCustom,
      'via_search': pick.viaSearch,
    });
    Navigator.of(context).pop(pick);
  }

  void _useCustom() {
    var q = _controller.text.trim();
    if (q.isEmpty) {
      _focus.requestFocus(); // 「Tulis sendiri」：聚焦搜索框、弹键盘
      return;
    }
    q = _clip(q, kBreedCustomMaxLength);
    _pick(BreedPick(q, resolveCustomBreed(widget.type, q), isCustom: true, viaSearch: true));
  }

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final q = _controller.text;
    final hasQuery = normalizeQuery(q).isNotEmpty;
    final current = widget.current;
    final rows = <Widget>[];
    if (_isOtherPicker) {
      final groups = hasQuery
          ? _otherGroupsFor(q)
          : [for (final g in kOtherSpeciesGroups) (g, g.breeds, true)];
      for (final (g, breeds, withLainnya) in groups) {
        rows.add(Padding(
          key: ValueKey('breedGroup_${g.code.wire}'),
          padding: const EdgeInsets.fromLTRB(12, 10, 12, 4),
          child: Text(g.titleId.toUpperCase(),
              style: const TextStyle(fontSize: 11, fontWeight: FontWeight.w600, color: AppColors.muted)),
        ));
        for (final b in breeds) {
          rows.add(_OptionRow(
            key: ValueKey('breedOption_${b.label}'),
            label: b.label,
            query: q,
            selected: !_currentIsCustom && current == b.label,
            onTap: () => _pick(BreedPick(b.label, g.code, viaSearch: hasQuery)),
          ));
        }
        if (withLainnya) {
          rows.add(_OptionRow(
            key: ValueKey('breedOption_lainnya_${g.code.wire}'),
            label: l10n.petSpeciesGroupOther(g.titleId),
            query: q,
            selected: !_currentIsCustom && current == g.titleId,
            onTap: () => _pick(BreedPick(g.titleId, g.code, viaSearch: hasQuery)),
          ));
        }
      }
    } else {
      for (final b in breedsFor(widget.type)) {
        if (hasQuery && !speciesQueryMatches(q, breedCandidates(b))) continue;
        rows.add(_OptionRow(
          key: ValueKey('breedOption_${b.label}'),
          label: b.label,
          query: q,
          selected: !_currentIsCustom && current == b.label,
          onTap: () => _pick(BreedPick(b.label, widget.type, viaSearch: hasQuery)),
        ));
      }
    }
    final Widget body = rows.isEmpty
        ? Padding(
            key: const ValueKey('breedPickerEmpty'),
            padding: const EdgeInsets.symmetric(vertical: 20),
            child: Text(l10n.petBreedNoResult,
                textAlign: TextAlign.center, style: const TextStyle(fontSize: 13, color: AppColors.ink2)),
          )
        : ListView(key: const ValueKey('breedPickerList'), shrinkWrap: true, children: rows);
    final shown = _clip(q.trim(), kBreedCustomMaxLength);
    final footer = _CustomRow(
      label: hasQuery ? l10n.petBreedUseCustom(shown) : l10n.petBreedWriteOwn,
      selected: _currentIsCustom && hasQuery && q.trim() == current?.trim(),
      onTap: _useCustom,
    );
    return SpeciesPickerScaffold(
      title: l10n.petProfileBreedPick,
      searchHint: l10n.petBreedSearchHint,
      controller: _controller,
      focusNode: _focus,
      body: body,
      footer: footer,
    );
  }
}

/// 底部固定一行：「✎ Tulis sendiri」/「✎ Pakai "{q}"」（图标放浅紫圆底）。
class _CustomRow extends StatelessWidget {
  const _CustomRow({required this.label, required this.selected, required this.onTap});

  final String label;
  final bool selected;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    return InkWell(
      key: const ValueKey('breedPickerCustom'),
      onTap: onTap,
      borderRadius: BorderRadius.circular(8),
      child: Ink(
        height: 48,
        decoration: BoxDecoration(
          color: selected ? AppColors.mintTint : null,
          borderRadius: BorderRadius.circular(8),
          border: const Border(top: BorderSide(color: AppColors.line)),
        ),
        child: Padding(
          padding: const EdgeInsets.symmetric(horizontal: 12),
          child: Row(
            children: [
              Container(
                width: 28,
                height: 28,
                decoration: const BoxDecoration(color: AppColors.mintTint, shape: BoxShape.circle),
                child: const Icon(Icons.edit_rounded, size: 15, color: AppColors.mint),
              ),
              const SizedBox(width: 10),
              Expanded(
                child: Text(label,
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                    style: TextStyle(
                        fontSize: 14,
                        color: selected ? AppColors.mint : AppColors.ink,
                        fontWeight: selected ? FontWeight.w600 : FontWeight.w500)),
              ),
              if (selected) const Icon(Icons.check_rounded, size: 18, color: AppColors.mint),
            ],
          ),
        ),
      ),
    );
  }
}
