import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/features/auth/domain/login_response.dart';

/// L0：专题线 video Story 1.5 · FR-3 —— /me 下发 vipLevel，缺省为 1。
void main() {
  test('有 vipLevel', () {
    final p = UserProfile.fromJson({'vipLevel': 10});
    expect(p.vipLevel, 10);
    expect(p.canPublishVideo, isTrue);
  });

  test('缺省 / null → 1，不能发视频', () {
    expect(UserProfile.fromJson({}).vipLevel, 1);
    expect(UserProfile.fromJson({'vipLevel': null}).canPublishVideo, isFalse);
    expect(UserProfile.fromJson({'vipLevel': 9}).canPublishVideo, isFalse);
  });

  test('copyWith 保留 / 覆盖 vipLevel', () {
    const p = UserProfile(vipLevel: 10);
    expect(p.copyWith(nickname: 'x').vipLevel, 10);
    expect(p.copyWith(vipLevel: 1).canPublishVideo, isFalse);
  });
}
