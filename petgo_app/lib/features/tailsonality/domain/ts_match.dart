/// 主人 × 宠物配型计算（V1.3.2 Story 2.5 · AD-3 · 内容设计 §4.3）。**纯客户端，不落库。**
///
/// 只比**四字母**，不比能量后缀（主人没有后缀）；入参必须是四字母，调用方负责去掉宠物的 `-H` / `-L`。
class TsMatch {
  const TsMatch({required this.sameCount, required this.diffLineKeys, required this.axisDetailKeys});

  /// 逐位相同字母数 0..4（= 埋点 `match_level`、= `kTsMatchTiers` 的键）。
  final int sameCount;

  /// 配型卡卡面差异句键（最多 2 条，Story 4.2 用）：不同的轴按 E/I > T/F > J/P > N/S 取前两条；
  /// 键 = 主人字母 + 宠物字母（`kTsAxisDiffLines`）；4/4 时为空。
  final List<String> diffLineKeys;

  /// 页内逐轴详解键：固定 E/I → N/S → T/F → J/P 四条，键 = 主人字母 + 宠物字母（`kTsAxisDetails`）。
  final List<String> axisDetailKeys;

  /// 配型插画档号 1..5（`match_tier<tier>.webp`；1 = 4/4 … 5 = 0/4）。与 [sameCount] 不是一回事。
  int get tier => 5 - sameCount;
}

final RegExp _fourLetters = RegExp(r'^[EI][NS][TF][JP]$');

/// 卡面差异句的轴优先级（位下标）：E/I(0) > T/F(2) > J/P(3) > N/S(1)。
const List<int> _diffPriority = [0, 2, 3, 1];

TsMatch computeTsMatch(String owner4, String pet4) {
  if (!_fourLetters.hasMatch(owner4) || !_fourLetters.hasMatch(pet4)) {
    throw ArgumentError('computeTsMatch expects two 4-letter codes');
  }
  var same = 0;
  for (var i = 0; i < 4; i++) {
    if (owner4[i] == pet4[i]) same++;
  }
  final diffs = [
    for (final i in _diffPriority)
      if (owner4[i] != pet4[i]) '${owner4[i]}${pet4[i]}',
  ];
  return TsMatch(
    sameCount: same,
    diffLineKeys: diffs.take(2).toList(growable: false),
    axisDetailKeys: [for (var i = 0; i < 4; i++) '${owner4[i]}${pet4[i]}'],
  );
}
