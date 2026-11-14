import 'package:flutter/gestures.dart' show kDoubleTapSlop;
import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/shared/media/feed_tap_arbiter.dart';
import 'package:tailtopia/shared/media/lightbox_gestures.dart';

/// V1.5.0 likes Story 4.1 · AC1（FR-209）：Feed 图片区单击 / 双击仲裁。
void main() {
  late int singles;
  late List<Offset> doubles;
  late FeedTapArbiter a;

  setUp(() {
    singles = 0;
    doubles = [];
    a = FeedTapArbiter(onSingleTap: () => singles++, onDoubleTap: doubles.add);
  });

  test('窗口复用大图页 220ms，不另定时长', () {
    expect(a.window, kLightboxSingleTapDelay);
    expect(a.slop, kDoubleTapSlop);
  });

  testWidgets('单击：220ms 后才触发（之前不触发）', (tester) async {
    a.tapUp(const Offset(10, 10));
    await tester.pump(const Duration(milliseconds: 219));
    expect(singles, 0);
    await tester.pump(const Duration(milliseconds: 1));
    expect(singles, 1);
    expect(doubles, isEmpty);
    await tester.pump(const Duration(seconds: 1)); // 清掉吞点期计时器
  });

  testWidgets('双击：窗口内第二下且距离在 slop 内 → 只双击、不单击', (tester) async {
    a.tapUp(const Offset(10, 10));
    await tester.pump(const Duration(milliseconds: 100));
    a.tapUp(const Offset(20, 15));
    await tester.pump(const Duration(seconds: 1));
    expect(doubles, [const Offset(20, 15)]);
    expect(singles, 0);
    await tester.pump(const Duration(seconds: 1)); // 清掉吞点期计时器
  });

  testWidgets('三连击：只赞一次、不进详情', (tester) async {
    a.tapUp(const Offset(10, 10));
    await tester.pump(const Duration(milliseconds: 80));
    a.tapUp(const Offset(10, 10));
    await tester.pump(const Duration(milliseconds: 80));
    a.tapUp(const Offset(10, 10));
    await tester.pump(const Duration(seconds: 1));
    expect(doubles, hasLength(1));
    expect(singles, 0);
    await tester.pump(const Duration(seconds: 1)); // 清掉吞点期计时器
  });

  testWidgets('两下超距：第一下立即单击，第二下重新计时', (tester) async {
    a.tapUp(const Offset(0, 0));
    await tester.pump(const Duration(milliseconds: 100));
    a.tapUp(Offset(kDoubleTapSlop + 1, 0));
    expect(singles, 1);
    await tester.pump(const Duration(milliseconds: 220));
    expect(singles, 2);
    expect(doubles, isEmpty);
    await tester.pump(const Duration(seconds: 1)); // 清掉吞点期计时器
  });

  testWidgets('第二下在窗口外：两次单击', (tester) async {
    a.tapUp(const Offset(10, 10));
    await tester.pump(const Duration(milliseconds: 300));
    a.tapUp(const Offset(10, 10));
    await tester.pump(const Duration(milliseconds: 300));
    expect(singles, 2);
    expect(doubles, isEmpty);
    await tester.pump(const Duration(seconds: 1)); // 清掉吞点期计时器
  });

  testWidgets('cancel：撤销待执行的单击（滚动 / 长按开始）', (tester) async {
    a.tapUp(const Offset(10, 10));
    a.cancel();
    await tester.pump(const Duration(seconds: 1));
    expect(singles, 0);
    await tester.pump(const Duration(seconds: 1)); // 清掉吞点期计时器
  });

  testWidgets('dispose：不再回调', (tester) async {
    a.tapUp(const Offset(10, 10));
    a.dispose();
    await tester.pump(const Duration(seconds: 1));
    expect(singles, 0);
    await tester.pump(const Duration(seconds: 1)); // 清掉吞点期计时器
  });
}
