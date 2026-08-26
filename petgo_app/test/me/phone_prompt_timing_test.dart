import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/features/me/domain/phone_prompt_timing.dart';

/// Story 7.2 · 时机判定（决策 X-21：**用户第 3 天打开 App 时提示**）。
///
/// 这四条判定是本 story 最容易做错的地方，每条都单独钉住：
/// ① 第 1 天 = 注册当天 ⇒ 第 3 天 = 注册日 +2 个自然日
/// ② 自然日按 **WIB（Asia/Jakarta，UTC+7）** 划界
/// ③ **第 3 天起（含）首次打开**即触发，**错过第 3 天不作废**
/// ④ 已填手机号者永不提示；已提示过不再提示
void main() {
  /// WIB = UTC+7。写成 UTC 时刻便于精确表达"当地某日某时"。
  DateTime wib(int y, int m, int d, [int h = 12, int min = 0]) =>
      DateTime.utc(y, m, d, h, min).subtract(const Duration(hours: 7));

  group('① 第 1 天 = 注册当天 ⇒ 第 3 天 = 注册日 +2', () {
    test('注册当天（第 1 天）不提示', () {
      expect(
        PhonePromptTiming.shouldPrompt(
          registeredAt: wib(2026, 8, 1, 10),
          now: wib(2026, 8, 1, 23),
          hasPhone: false,
          alreadyPrompted: false,
        ),
        isFalse,
      );
    });

    test('第 2 天不提示', () {
      expect(
        PhonePromptTiming.shouldPrompt(
          registeredAt: wib(2026, 8, 1, 10),
          now: wib(2026, 8, 2, 23),
          hasPhone: false,
          alreadyPrompted: false,
        ),
        isFalse,
      );
    });

    test('第 3 天提示', () {
      expect(
        PhonePromptTiming.shouldPrompt(
          registeredAt: wib(2026, 8, 1, 10),
          now: wib(2026, 8, 3, 0, 5),
          hasPhone: false,
          alreadyPrompted: false,
        ),
        isTrue,
      );
    });
  });

  group('② 自然日按 WIB 划界，用**日历日差**而不是小时差', () {
    /// 🔴 这是最容易写错的一条：注册当天 23:00 与次日 01:00 只相差 **2 小时**，
    /// 但那是**两个自然日**。若用 `(now - registeredAt).inDays >= 2` 来算，
    /// 第 3 天会被推迟将近一天 —— 边界上的用户体验不一致。
    test('注册当天 23:00 → 第 3 天 00:30 即满足（相隔仅 25.5 小时）', () {
      expect(
        PhonePromptTiming.shouldPrompt(
          registeredAt: wib(2026, 8, 1, 23),
          now: wib(2026, 8, 3, 0, 30),
          hasPhone: false,
          alreadyPrompted: false,
        ),
        isTrue,
        reason: '日历日差为 2（8/1 → 8/3），与相隔小时数无关',
      );
    });

    /// 反向：相隔近 48 小时但仍是第 2 天 → 不提示。
    test('注册当天 00:30 → 第 2 天 23:00 不满足（相隔近 47 小时）', () {
      expect(
        PhonePromptTiming.shouldPrompt(
          registeredAt: wib(2026, 8, 1, 0, 30),
          now: wib(2026, 8, 2, 23),
          hasPhone: false,
          alreadyPrompted: false,
        ),
        isFalse,
      );
    });

    /// 🛡 WIB 划界而非 UTC：UTC 的午夜在雅加达是早上 7 点。
    /// 若按 UTC 算日期，8/3 06:00 WIB（= 8/2 23:00 UTC）会被判成第 2 天，
    /// 而用户感知的分明已经是第 3 天早上。
    test('第 3 天清晨 06:00 WIB 提示（按 UTC 算会误判为第 2 天）', () {
      expect(
        PhonePromptTiming.shouldPrompt(
          registeredAt: wib(2026, 8, 1, 10),
          now: wib(2026, 8, 3, 6),
          hasPhone: false,
          alreadyPrompted: false,
        ),
        isTrue,
      );
    });
  });

  group('③ 🔴 错过第 3 天不作废', () {
    /// 🔴 **本 story 最要紧的一条。** 产品说的是"第 3 天打开时"，
    /// 落地必须是"第 3 天**及以后**首次打开时" ——
    /// 只在第 3 天当天提示，会让那天没打开 App 的用户**永远**收不到，
    /// 与本 FR 要解决的问题（拿到愿意留号码的人的联系方式）恰好相反。
    test('第 5 天才打开 → 仍然提示', () {
      expect(
        PhonePromptTiming.shouldPrompt(
          registeredAt: wib(2026, 8, 1, 10),
          now: wib(2026, 8, 5, 9),
          hasPhone: false,
          alreadyPrompted: false,
        ),
        isTrue,
      );
    });

    test('第 300 天才打开 → 仍然提示（存量用户也该有这一次机会）', () {
      expect(
        PhonePromptTiming.shouldPrompt(
          registeredAt: wib(2025, 10, 1, 10),
          now: wib(2026, 8, 1, 9),
          hasPhone: false,
          alreadyPrompted: false,
        ),
        isTrue,
      );
    });
  });

  group('④ 两个终止条件', () {
    test('已填手机号 → 永不提示（即使第 3 天到了）', () {
      expect(
        PhonePromptTiming.shouldPrompt(
          registeredAt: wib(2026, 8, 1, 10),
          now: wib(2026, 8, 9, 10),
          hasPhone: true,
          alreadyPrompted: false,
        ),
        isFalse,
      );
    });

    test('已提示过 → 不再提示（填了或跳过都算用掉）', () {
      expect(
        PhonePromptTiming.shouldPrompt(
          registeredAt: wib(2026, 8, 1, 10),
          now: wib(2026, 8, 9, 10),
          hasPhone: false,
          alreadyPrompted: true,
        ),
        isFalse,
      );
    });
  });

  group('缺注册时间时的兜底', () {
    /// 拿不到注册时间（老版本服务端 / 请求失败）→ **不提示**。
    /// 宁可少问一次，也不要在算不出"第几天"的情况下瞎问 ——
    /// 这条 FR 的全部价值在于"在合适的时机问"。
    test('registeredAt 为 null → 不提示', () {
      expect(
        PhonePromptTiming.shouldPrompt(
          registeredAt: null,
          now: wib(2026, 8, 9, 10),
          hasPhone: false,
          alreadyPrompted: false,
        ),
        isFalse,
      );
    });
  });
}
