import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/features/user_profile/data/public_profile_repository.dart';

/// L0：公开主页投影的**线上格式**契约（V1.3.0 batch-b1 Story 2.1 · AC1/AC5）。
///
/// 对照后端 `PublicProfileResponse` 的两条硬约束：
/// ① 全局 Jackson `NON_NULL` 会把 null 字段**整个键省略**，所以解析侧必须能吃「键不在」；
/// ② 已注销时服务端一个身份字段都不给（NFR-3）。
void main() {
  test('AC1：完整响应逐字段解析', () {
    final p = PublicProfile.fromJson(<String, dynamic>{
      'nickname': 'Rina',
      'avatarUrl': 'https://cdn/r.jpg',
      'signature': 'Pecinta kucing',
      'tags': [
        {'code': 'KOL', 'name': 'Kreator', 'icon': '⭐', 'description': 'Kreator pilihan',
          'badgeColor': '#F6A609'},
      ],
      'joinedAt': '2026-03-04T05:06:07Z',
      'postCount': 18,
      'self': false,
      'isDeactivated': false,
      'reported': true,
    });

    expect(p.nickname, 'Rina');
    expect(p.avatarUrl, 'https://cdn/r.jpg');
    expect(p.signature, 'Pecinta kucing');
    expect(p.tags.single.code, 'KOL');
    expect(p.postCount, 18);
    expect(p.self, isFalse);
    expect(p.isDeactivated, isFalse);
    expect(p.reported, isTrue);
    expect(p.joinedAt, DateTime.utc(2026, 3, 4, 5, 6, 7));
  });

  /// 🔴 游客的响应体里**根本没有 `reported` 这个键**（后端可空布尔 + NON_NULL 省略）。
  /// 解析成 null 再当真值用就会崩；这里必须稳稳落成 false。
  test('AC1：游客响应（缺 reported / 缺 tags）不崩，reported=false', () {
    final p = PublicProfile.fromJson(<String, dynamic>{
      'nickname': 'Rina',
      'joinedAt': '2026-03-04T05:06:07Z',
      'postCount': 0,
      'self': false,
      'isDeactivated': false,
    });

    expect(p.reported, isFalse);
    expect(p.tags, isEmpty);
    expect(p.avatarUrl, isNull);
    expect(p.signature, isNull);
  });

  /// AC5 / NFR-3：注销 → 昵称 / 头像 / 签名 / 标签 / **加入时间**一个都不下发。
  /// 「这个账号是 2024 年注册的」同样是身份信息。
  test('AC5：注销响应里没有任何身份字段', () {
    final p = PublicProfile.fromJson(<String, dynamic>{
      'postCount': 0,
      'self': false,
      'isDeactivated': true,
    });

    expect(p.isDeactivated, isTrue);
    expect(p.nickname, isNull);
    expect(p.avatarUrl, isNull);
    expect(p.signature, isNull);
    expect(p.joinedAt, isNull);
    expect(p.tags, isEmpty);
  });

  /// 🛡 `joinedAt` 缺失 / 不是合法时间串 → null，**不抛**：
  /// 一个格式不对的时间戳不该让整页主页打不开。
  test('joinedAt 缺失或畸形 → null，不抛', () {
    expect(PublicProfile.fromJson(<String, dynamic>{'postCount': 0}).joinedAt, isNull);
    expect(
      PublicProfile.fromJson(<String, dynamic>{'postCount': 0, 'joinedAt': 'not-a-date'}).joinedAt,
      isNull,
    );
  });
}
