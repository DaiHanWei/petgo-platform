import 'package:dio/dio.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/core/mock/mock_backend.dart';

// 被对账的 App 侧 DTO（真 fromJson）。
import 'package:tailtopia/features/auth/domain/login_response.dart';
import 'package:tailtopia/features/consult/domain/consult_history_item.dart';
import 'package:tailtopia/features/consult/domain/consult_session.dart';
import 'package:tailtopia/features/content/data/like_repository.dart';
import 'package:tailtopia/features/content/data/mini_profile_repository.dart';
import 'package:tailtopia/features/content/domain/comment.dart';
import 'package:tailtopia/features/content/domain/content_detail.dart';
import 'package:tailtopia/features/content/domain/feed_item.dart';
import 'package:tailtopia/features/me/data/my_posts_repository.dart';
import 'package:tailtopia/features/media/data/upload_ticket.dart';
import 'package:tailtopia/features/notify/data/app_version_repository.dart';
import 'package:tailtopia/features/notify/domain/notification_item.dart';
import 'package:tailtopia/features/profile/domain/milestone.dart';
import 'package:tailtopia/features/profile/domain/pet_profile.dart';
import 'package:tailtopia/features/profile/domain/timeline_item.dart';
import 'package:tailtopia/features/triage/data/triage_repository.dart';
import 'package:tailtopia/features/vet/domain/vet_login_response.dart';

/// L0 契约对账（CROSS-STORY-DECISIONS C5 ②：**App mock ↔ data DTO 字段一致**）。
///
/// 直接驱动真 [MockBackend] → 跑真 `fromJson`，无 Dio / 无网络 / 无 DB → 云端 headless 可跑。
///
/// 两组防线：
///  - **Group 1 字段集金标**：对已逐字核对过后端 `*Response` 的契约，钉死 mock 产出的**精确字段集**。
///    mock 多一个字段（typo）/ 少一个必填字段 → 立即红。
///  - **Group 2 round-trip**：其余可读端点，驱动 mock → 真 `fromJson`。mock 缺任一 DTO 必读字段
///    （非空 cast / DateTime.parse 失败）→ 抛异常即红。广覆盖「mock 漂离 App 需求」。
///
/// **约定**：mock 内部辅助键以 `_` 前缀（如 `_token`），不属对外契约，对账时剔除。
void main() {
  final mock = MockBackend.instance;

  /// 驱动 mock，断言命中且 2xx，返回 data Map。
  Map<String, dynamic> call(String method, String path, [Map<String, dynamic>? body]) {
    final res = mock.handle(RequestOptions(path: path, method: method, data: body));
    expect(res, isNotNull, reason: '$method $path 未被 mock 命中');
    expect(res!.statusCode, inInclusiveRange(200, 299), reason: '$method $path → ${res.statusCode}');
    return (res.data as Map).cast<String, dynamic>();
  }

  /// 对外契约字段集（剔除 `_` 前缀的 mock 内部键）。
  Set<String> contractKeys(Map<dynamic, dynamic> m) =>
      m.keys.map((e) => e.toString()).where((k) => !k.startsWith('_')).toSet();

  Map<String, dynamic> firstItem(Map<String, dynamic> envelope) {
    final items = envelope['items'] as List;
    expect(items, isNotEmpty, reason: '需至少一条 item 才能对账卡片字段');
    return (items.first as Map).cast<String, dynamic>();
  }

  group('Group 1 · 字段集金标（mock 精确字段集 == 后端契约）', () {
    test('Feed 信封 {items,nextCursor,hasMore} + 卡片 10 字段', () {
      final env = call('GET', '/api/v1/content-posts');
      expect(contractKeys(env), {'items', 'nextCursor', 'hasMore'});
      expect(contractKeys(firstItem(env)), {
        'id', 'authorId', 'authorNickname', 'authorAvatarUrl', 'authorDeleted',
        'type', 'body', 'firstImageUrl', 'likeCount', 'createdAt',
      });
      final page = FeedPage.fromJson(env); // 真解析不抛
      expect(page.items, isNotEmpty);
    });

    test('VetMe {id,displayName,status}', () {
      final m = call('GET', '/api/v1/vet/me');
      expect(contractKeys(m), {'id', 'displayName', 'status'});
      VetMe.fromJson(m);
    });

    test('OnlineStatus {online,status}', () {
      final get = call('GET', '/api/v1/vet/online-status');
      expect(contractKeys(get), {'online', 'status'});
      final put = call('PUT', '/api/v1/vet/online-status', {'online': false});
      expect(contractKeys(put), {'online', 'status'});
      expect(put['status'], 'OFFLINE'); // online=false → status 同步
    });

    test('ConsultAvailability {vetOnline,expectedWindow=文案key}', () {
      final m = call('GET', '/api/v1/consult/availability');
      expect(contractKeys(m), {'vetOnline', 'expectedWindow'});
      expect(m['expectedWindow'], 'WEEKDAY_8_23'); // 文案 key 而非渲染文本
      ConsultAvailability.fromJson(m);
    });

    test('AppVersion {latestVersion,minSupportedVersion,iosStoreUrl,androidStoreUrl}', () {
      final m = call('GET', '/api/v1/app-version');
      expect(contractKeys(m),
          {'latestVersion', 'minSupportedVersion', 'iosStoreUrl', 'androidStoreUrl'});
      AppVersionInfo.fromJson(m);
    });

    test('MiniProfile {nickname,avatarUrl,postCount,isDeactivated}', () {
      final m = call('GET', '/api/v1/users/5/mini-profile');
      expect(contractKeys(m), {'nickname', 'avatarUrl', 'postCount', 'isDeactivated'});
      MiniProfile.fromJson(m);
    });

    test('Notification 信封 + 条目 7 字段 + type 为合法枚举', () {
      final env = call('GET', '/api/v1/notifications');
      expect(contractKeys(env), {'items', 'nextCursor', 'hasMore'});
      final item = firstItem(env);
      expect(contractKeys(item),
          {'type', 'title', 'body', 'deepLinkType', 'deepLinkToken', 'read', 'createdAt'});
      // type 必须是后端 NotificationType 合法值。
      const legal = {
        'VET_REPLY', 'CONSULT_CLOSED', 'CONTENT_LIKED', 'CONTENT_COMMENTED', 'NEW_CONSULT_REQUEST',
        'PET_BIRTHDAY', 'COMPANION_ANNIVERSARY', 'MILESTONE_NODE' // 6-7/8-6 定时/达成类
      };
      for (final n in env['items'] as List) {
        expect(legal, contains((n as Map)['type']), reason: '非法通知 type: ${n['type']}');
      }
      NotificationPage.fromJson(env);
    });

    test('Milestone 列表 5 字段 + group 4 字段 + item 字段集（C5 · Story 8.2）', () {
      final m = call('GET', '/api/v1/pet-profiles/me/milestones');
      // petAvatarUrl 可省略（NON_NULL）；seed 档案有头像 → 5 字段。
      expect(contractKeys(m),
          {'petName', 'petAvatarUrl', 'completedCount', 'totalCount', 'groups'});
      final groups = m['groups'] as List;
      expect(groups, isNotEmpty);
      final g0 = (groups.first as Map).cast<String, dynamic>();
      expect(contractKeys(g0), {'level', 'completedCount', 'totalCount', 'items'});
      // 每个 item 字段须 ⊆ 契约集且含必填；已完成项带 completedAt，未完成省略。
      const allowed = {'code', 'title', 'level', 'triggerType', 'completed', 'completedAt'};
      const required = {'code', 'title', 'level', 'triggerType', 'completed'};
      var sawCompleted = false;
      for (final grp in groups) {
        for (final it in (grp as Map)['items'] as List) {
          final keys = contractKeys(it as Map);
          expect(keys.difference(allowed), isEmpty, reason: 'mock 多字段: $keys');
          expect(required.difference(keys), isEmpty, reason: 'mock 缺必填: $keys');
          if (it['completed'] == true) {
            sawCompleted = true;
            expect(keys, contains('completedAt'));
          } else {
            expect(keys, isNot(contains('completedAt')));
          }
        }
      }
      expect(sawCompleted, isTrue, reason: 'mock 应含至少一个已完成里程碑（彩色徽章演示）');
      MilestoneList.fromJson(m); // 真解析不抛
    });

    test('Milestone 打卡候选 item 字段集（C5 · Story 8.4）', () {
      final env = call('GET', '/api/v1/pet-profiles/me/milestones/checkin-candidates');
      expect(contractKeys(env), {'items'});
      const allowed = {'contentId', 'firstImageUrl', 'eventDate', 'text', 'linked'};
      const required = {'contentId', 'linked'};
      final items = env['items'] as List;
      expect(items, isNotEmpty);
      for (final it in items) {
        final keys = contractKeys(it as Map);
        expect(keys.difference(allowed), isEmpty, reason: 'mock 多字段: $keys');
        expect(required.difference(keys), isEmpty, reason: 'mock 缺必填: $keys');
        MilestoneCheckinCandidate.fromJson((it).cast<String, dynamic>());
      }
    });
  });

  group('Group 2 · round-trip（驱动 mock → 真 fromJson 不抛）', () {
    test('GET /me → UserProfile', () {
      UserProfile.fromJson(call('GET', '/api/v1/me'));
    });

    test('GET /me/posts → MyPost', () {
      final env = call('GET', '/api/v1/me/posts');
      for (final e in env['items'] as List) {
        MyPost.fromJson((e as Map).cast<String, dynamic>());
      }
    });

    test('GET /content-posts/{id} → ContentDetail', () {
      ContentDetail.fromJson(call('GET', '/api/v1/content-posts/100'));
    });

    test('POST + GET /content-posts/{id}/comments → Comment / CommentPage', () {
      final created = call('POST', '/api/v1/content-posts/100/comments', {'body': '测试评论'});
      Comment.fromJson(created);
      CommentPage.fromJson(call('GET', '/api/v1/content-posts/100/comments'));
    });

    test('POST /content-posts/{id}/like → LikeResult', () {
      LikeResult.fromJson(call('POST', '/api/v1/content-posts/100/like'));
    });

    test('GET /pet-profiles/me → PetProfile', () {
      PetProfile.fromJson(call('GET', '/api/v1/pet-profiles/me'));
    });

    test('GET /pet-profiles/me/timeline → TimelinePage', () {
      TimelinePage.fromJson(call('GET', '/api/v1/pet-profiles/me/timeline'));
    });

    test('POST /triage + GET /triage/{id} → TriageResult（三态）', () {
      final accepted = call('POST', '/api/v1/triage', {'petStatus': 'HAS_PET'});
      final id = accepted['triageId'];
      TriageResult.fromJson(call('GET', '/api/v1/triage/$id'));
    });

    test('POST /consult-sessions + GET active → ConsultSession', () {
      ConsultSession.fromJson(call('POST', '/api/v1/consult-sessions', {'source': 'DIRECT'}));
      ConsultSession.fromJson(call('GET', '/api/v1/consult-sessions/active'));
    });

    test('GET /consult/history → ConsultHistoryPage', () {
      ConsultHistoryPage.fromJson(call('GET', '/api/v1/consult/history'));
    });

    test('POST /media/upload-url → UploadTicket', () {
      UploadTicket.fromJson(call('POST', '/api/v1/media/upload-url', {'scope': 'PUBLIC'}));
    });
  });
}
