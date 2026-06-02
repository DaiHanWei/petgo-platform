import 'package:flutter_test/flutter_test.dart';
import 'package:petgo/features/notify/domain/app_version_check.dart';

/// Story 6.5 J4：语义版本比较 + 三态判定 + 平台 URL 选择（含相等/补丁/预发边界）。
void main() {
  test('compareSemver：大小/相等/缺位补0/预发后缀忽略', () {
    expect(AppVersionCheck.compareSemver('1.0.0', '1.0.1'), -1);
    expect(AppVersionCheck.compareSemver('1.2.0', '1.1.9'), 1);
    expect(AppVersionCheck.compareSemver('1.0', '1.0.0'), 0);
    expect(AppVersionCheck.compareSemver('2.0.0', '1.9.9'), 1);
    expect(AppVersionCheck.compareSemver('1.0.0-beta', '1.0.0'), 0); // 忽略预发后缀
  });

  test('decide：强制 / 推荐 / 无更新三态', () {
    // 当前 < minSupported → 强制
    expect(AppVersionCheck.decide(current: '1.0.0', latest: '2.0.0', minSupported: '1.5.0'),
        UpdateDecision.forced);
    // minSupported ≤ 当前 < latest → 推荐
    expect(AppVersionCheck.decide(current: '1.6.0', latest: '2.0.0', minSupported: '1.5.0'),
        UpdateDecision.recommended);
    // 当前 ≥ latest → 无更新
    expect(AppVersionCheck.decide(current: '2.0.0', latest: '2.0.0', minSupported: '1.5.0'),
        UpdateDecision.none);
    expect(AppVersionCheck.decide(current: '2.1.0', latest: '2.0.0', minSupported: '1.5.0'),
        UpdateDecision.none);
  });

  test('storeUrl：平台选择 + 空兜底', () {
    expect(
        AppVersionCheck.storeUrl(isIos: true, iosStoreUrl: 'ios://x', androidStoreUrl: 'play://y'),
        'ios://x');
    expect(
        AppVersionCheck.storeUrl(isIos: false, iosStoreUrl: 'ios://x', androidStoreUrl: 'play://y'),
        'play://y');
    expect(AppVersionCheck.storeUrl(isIos: true, iosStoreUrl: '', androidStoreUrl: 'play://y'),
        isNull);
  });
}
