import 'dart:math' as math;

/// 护照分享卡章格的排版结果（V1.3.2 Story 4.3 · AC1.2）。
class PassportShareGridLayout {
  const PassportShareGridLayout({
    required this.columns,
    required this.rows,
    required this.cellWidth,
    required this.cellHeight,
    required this.visibleStamps,
    required this.overflowCount,
  });

  final int columns;
  final int rows;
  final double cellWidth;
  final double cellHeight;

  /// 画出来的章数（不含「+N」格）。
  final int visibleStamps;

  /// 末格「+N」的 N；0 = 不折叠。
  final int overflowCount;
}

/// 章格自适应 + `+N` 折叠（纯函数，单位 = 画布像素）。
///
/// - 列数从 [minColumns] 起逐个试，取**第一个装得下全部章**的列数（cell 尽量大）；
/// - cell 宽不得小于 [minCellWidth]（AC：画布宽的 1/6）；到了下限仍装不下 →
///   按下限能放的最大格数排，末格折叠为「+N」（N = 未显示的章数；做法同庆祝页 KOLEKSI）。
/// - 🔴 **不产出任何总数分母**（FR-120.4）：只有「画了几个 / 折叠了几个」。
PassportShareGridLayout passportShareGrid({
  required double width,
  required double height,
  required int count,
  required double minCellWidth,
  double gap = 24,
  double cellAspect = 1.25,
  int minColumns = 2,
}) {
  if (count <= 0) {
    return const PassportShareGridLayout(
        columns: 0, rows: 0, cellWidth: 0, cellHeight: 0, visibleStamps: 0, overflowCount: 0);
  }
  for (var c = minColumns;; c++) {
    final cellW = (width - gap * (c - 1)) / c;
    if (cellW < minCellWidth) break;
    final rows = (count + c - 1) ~/ c;
    final cellH = cellW * cellAspect;
    if (rows * cellH + gap * (rows - 1) <= height) {
      return PassportShareGridLayout(
          columns: c, rows: rows, cellWidth: cellW, cellHeight: cellH, visibleStamps: count, overflowCount: 0);
    }
  }
  // 下限尺寸下能放的最大格数。
  final cols = math.max(1, ((width + gap) / (minCellWidth + gap)).floor());
  final cellW = (width - gap * (cols - 1)) / cols;
  final cellH = cellW * cellAspect;
  final rows = math.max(1, ((height + gap) / (cellH + gap)).floor());
  final capacity = cols * rows;
  if (count <= capacity) {
    return PassportShareGridLayout(
        columns: cols,
        rows: (count + cols - 1) ~/ cols,
        cellWidth: cellW,
        cellHeight: cellH,
        visibleStamps: count,
        overflowCount: 0);
  }
  final shown = capacity - 1; // 留一格给「+N」
  return PassportShareGridLayout(
      columns: cols,
      rows: rows,
      cellWidth: cellW,
      cellHeight: cellH,
      visibleStamps: shown,
      overflowCount: count - shown);
}
