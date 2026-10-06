import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/core/analytics/firebase_stats.dart';

/// L0：Firebase 日活统计的降级与开关（spec-v132-ga4-dau-daily-report）。
void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  test('debug / 测试构建不向 GA4 上报（测试数据不进生产口径）', () {
    expect(FirebaseStats.collectionEnabled, isFalse);
  });

  test('无原生插件（初始化必然失败）时 init 吞错不抛，且幂等', () async {
    await expectLater(FirebaseStats.init(), completes);
    await expectLater(FirebaseStats.init(), completes);
  });
}
