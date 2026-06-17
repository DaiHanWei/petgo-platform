import 'package:dio/dio.dart';
import 'package:flutter/foundation.dart';

/// 内存假后端（Mock 模式核心）。
///
/// 单例,随 APP 进程生命周期存活 → **重启即重置回初始种子**(创建项消失,符合需求)。
/// 按 `(method, path)` 解析请求,返回与各 model `fromJson` 匹配的 JSON;
/// 写入类端点改内存态,后续读端点反映变更(发帖/评论/点赞/改昵称/改状态/分诊/会话/通知已读…)。
class MockBackend {
  MockBackend._() {
    _seed();
  }
  static final MockBackend instance = MockBackend._();

  int _id = 1000;
  int _nextId() => ++_id;

  static String _iso(Duration ago) =>
      DateTime.now().toUtc().subtract(ago).toIso8601String();

  /// yyyy-MM-dd（事件日期，F9）。
  static String _date(Duration ago) {
    final d = DateTime.now().toUtc().subtract(ago);
    return '${d.year}-${d.month.toString().padLeft(2, '0')}-${d.day.toString().padLeft(2, '0')}';
  }

  // ---- 内存态 ----
  late Map<String, dynamic> _profile;
  final List<Map<String, dynamic>> _feed = [];
  final Map<int, List<Map<String, dynamic>>> _comments = {};
  final Map<int, List<Map<String, dynamic>>> _replies = {}; // 一级评论 id → 二级回复
  final List<Map<String, dynamic>> _notifications = [];
  final List<Map<String, dynamic>> _consultHistory = [];
  final List<Map<String, dynamic>> _timeline = [];
  Map<String, dynamic>? _petProfile;
  final Map<int, String> _triageLevel = {}; // triageId → GREEN/YELLOW/RED
  final List<String> _triageCycle = ['GREEN', 'YELLOW', 'RED'];
  int _triageSeq = 0;
  Map<String, dynamic>? _activeSession;

  /// 兽医工作台 demo 会话元数据（id → source）。待接单池 8101-8103 + 进行中 8001/8002。
  static const Map<int, String> _vetSessionSource = {
    8100: 'AI_UPGRADE', 8101: 'AI_UPGRADE', 8102: 'AI_UPGRADE', 8103: 'DIRECT', // 待接单池
    8001: 'AI_UPGRADE', 8002: 'DIRECT', // 进行中
  };
  /// 会话宠物身份样本（Story 5.5 顶栏；Mock 先做满、后端随后补）。未命中 → 取 8101(Oyen) 兜底，
  /// 保证 dev 深链 /vet/conversation/:id 顶栏也有真身份可看。
  static const Map<int, Map<String, Object>> _vetSessionPet = {
    8100: {'petName': 'Benji', 'petSpecies': 'DOG', 'petSex': 'MALE', 'petAgeMonths': 60, 'ownerHandle': 'bagas'},
    8101: {'petName': 'Oyen', 'petSpecies': 'CAT', 'petSex': 'MALE', 'petAgeMonths': 24, 'ownerHandle': 'rani'},
    8102: {'petName': 'Bruno', 'petSpecies': 'DOG', 'petSex': 'MALE', 'petAgeMonths': 36, 'ownerHandle': 'dimas'},
    8103: {'petName': 'Mochi', 'petSpecies': 'CAT', 'petSex': 'FEMALE', 'petAgeMonths': 8, 'ownerHandle': 'aditya'},
    8001: {'petName': 'Oyen', 'petSpecies': 'CAT', 'petSex': 'MALE', 'petAgeMonths': 24, 'ownerHandle': 'rani'},
    8002: {'petName': 'Milo', 'petSpecies': 'DOG', 'petSex': 'MALE', 'petAgeMonths': 18, 'ownerHandle': 'putri'},
  };
  /// 待接单池：未接单前 GET 返回 WAITING（让请求预览页不被「已被抢」误判弹出）。
  static const Set<int> _vetWaitingPool = {8100, 8101, 8102, 8103};
  final Set<int> _vetAccepted = {}; // 已接单的 session id（接单后转 IN_PROGRESS）

  void _seed() {
    _profile = {
      'id': 1,
      'nickname': 'Demo User',
      'displayName': 'Demo User',
      'email': 'demo@petgo.app',
      'avatarUrl': null,
      'petStatus': 'HAS_PET', // 有宠物 → 解锁成长档案
      'onboardingCompleted': true,
      'hasPetProfile': true,
      'role': 'USER',
    };

    // 假数据：贴合 10 张真实宠物照片的英文文案 + asset 封面图（部分多图供详情轮播）。
    // 图片走 `asset:` 前缀，经 AppImage 解析为打包资源；Feed 卡片 + 详情页均渲染真图。
    const a = 'asset:assets/seed/';
    final samples = <Map<String, dynamic>>[
      {'type': 'DAILY', 'body': 'Oyen chasing the sunset on the rooftop again — that golden fur is unreal 🐱', 'nick': 'Putri', 'like': 124,
        'imgs': ['${a}pet01.jpg', '${a}pet08.jpg']},
      {'type': 'DAILY', 'body': 'New collar day, and those eyes are wide as saucers 😺', 'nick': 'Sari', 'like': 88,
        'imgs': ['${a}pet07.jpg']},
      {'type': 'GROWTH_MOMENT', 'body': 'Look closely — she was born with a little heart on her fur 🩶', 'nick': null, 'like': 256,
        'imgs': ['${a}pet02.jpg', '${a}pet07.jpg']},
      {'type': 'DAILY', 'body': 'Took the golden boy out for a walk — he fetches like his life depends on it 🐕', 'nick': 'Budi', 'like': 73,
        'imgs': ['${a}pet08.jpg']},
      {'type': 'DAILY', 'body': 'His majesty the Maine Coon and that regal side profile 😼', 'nick': 'Andi', 'like': 61,
        'imgs': ['${a}pet03.jpg']},
      {'type': 'DAILY', 'body': 'Two fluffballs in one frame — what is the bottom one even thinking? 😹', 'nick': 'Maya', 'like': 142,
        'imgs': ['${a}pet04.jpg', '${a}pet03.jpg', '${a}pet05.jpg']},
      {'type': 'DAILY', 'body': 'Caught the cat doing some mysterious midnight dance moves — debut soon? 💃', 'nick': 'Dewi', 'like': 99,
        'imgs': ['${a}pet05.jpg']},
      {'type': 'GROWTH_MOMENT', 'body': 'Bath time! The golden retriever turned into a bubble monster 🛁', 'nick': null, 'like': 47,
        'imgs': ['${a}pet10.jpg']},
      {'type': 'DAILY', 'body': 'Gave the bull terrier a little bob cut — instant artsy vibes 💇', 'nick': 'Joko', 'like': 210,
        'imgs': ['${a}pet09.jpg']},
      {'type': 'KNOWLEDGE', 'body': 'Drew my two as a "frenemies" sketch — plus a quick guide to a peaceful multi-cat home 🎨', 'nick': 'Rina', 'like': 35,
        'imgs': ['${a}pet06.jpg']},
    ];
    for (var i = 0; i < samples.length; i++) {
      final s = samples[i];
      _feed.add(_post(
        id: 100 + i,
        type: s['type'] as String,
        body: s['body'] as String?,
        nickname: s['nick'] as String?,
        likeCount: s['like'] as int,
        images: (s['imgs'] as List).cast<String>(),
        ago: Duration(hours: i * 5 + 1),
      ));
    }
    // 详情页评论种子(给第一条帖)；第一条挂 2 条二级回复（replyCount=2 触发「查看回复」）。
    _comments[100] = [
      _comment(id: 900, nickname: 'Rina', body: 'So adorable! 😍', ago: const Duration(hours: 1))..['replyCount'] = 2,
      _comment(id: 901, nickname: 'Joko', body: 'I have the same little guy, haha', ago: const Duration(hours: 2)),
    ];
    _replies[900] = [
      _comment(id: 9001, nickname: 'Sari', body: 'Right?! Those eyes 😍', ago: const Duration(minutes: 40)),
      _comment(id: 9002, nickname: 'Budi', body: 'Mine strikes the exact same pose haha', ago: const Duration(minutes: 55)),
    ];

    _notifications.addAll([
      // type 必须是后端 NotificationType 合法值（VET_REPLY/CONSULT_CLOSED/CONTENT_LIKED/CONTENT_COMMENTED/NEW_CONSULT_REQUEST）。
      _notif(token: 'n1', type: 'CONTENT_COMMENTED', title: 'New comment', body: 'Rina commented on your post', read: false, ago: const Duration(minutes: 20)),
      _notif(token: 'n2', type: 'CONTENT_LIKED', title: 'New like', body: 'Budi liked your post', read: false, ago: const Duration(hours: 3)),
      _notif(token: 'n3', type: 'VET_REPLY', title: 'Consult update', body: 'A vet has replied to your consultation', read: true, ago: const Duration(days: 1)),
      // L 级里程碑达成推送（Story 8.6）：6-6 铃铛里程碑条改真数据驱动。深链跳里程碑列表页。
      _notif(token: 'n4', type: 'MILESTONE_NODE', title: 'Milestone unlocked 🎉', body: '"100 days together" is unlocked — take a look', read: false, ago: const Duration(hours: 1)),
    ]);

    _consultHistory.addAll([
      {
        'type': 'AI', 'date': _iso(const Duration(hours: 6)), 'triageId': 5001,
        'dangerLevel': 'GREEN', 'symptomSummary': 'Occasional sneezing, energy and appetite normal',
      },
      {
        'type': 'AI', 'date': _iso(const Duration(days: 1)), 'triageId': 5002,
        'dangerLevel': 'RED', 'symptomSummary': 'Ate chocolate, now vomiting',
      },
      {
        'type': 'VET', 'date': _iso(const Duration(days: 2)), 'sessionId': 6001,
        'vetDisplayName': 'Drh. Andi', 'sessionSummary': 'Cat has a fever — advised monitoring and hydration',
        'userStars': 5, 'archived': true, 'terminalState': 'CLOSED',
      },
      {
        'type': 'VET', 'date': _iso(const Duration(days: 4)), 'sessionId': 6002,
        'vetDisplayName': 'Drh. Maya', 'sessionSummary': null,
        'userStars': null, 'archived': false, 'terminalState': 'CLOSED',
      },
    ]);

    _petProfile = {
      'id': 7001, 'name': 'Oyen', 'cardToken': 'mock-card-token-oyen',
      'petType': 'CAT', 'avatarUrl': 'asset:assets/seed/pet01.jpg', 'breed': 'Orange Tabby', 'birthday': '2022-05-01',
      'intro': 'A little orange cat who loves napping and sunbathing', 'createdAt': _iso(const Duration(days: 200)),
    };

    // 成长时间线种子（快乐时刻 9 + 健康事件 5，跨 ~4 周分布）。
    // 快乐时刻带 eventDate（F9）+ pet 照片，postId 指向真实存在的 Feed 帖（100~109）可点进详情；
    // 健康事件取 date、绿/黄/红混合。日历 /day / archive-stats 全部从此聚合，自动丰满。
    const sa = 'asset:assets/seed/';
    _timeline.addAll([
      {'kind': 'HAPPY_MOMENT', 'date': _iso(const Duration(hours: 2)), 'eventDate': _date(Duration.zero), 'postId': 100, 'imageUrls': ['${sa}pet01.jpg', '${sa}pet08.jpg'], 'text': 'Rooftop sunset session with Oyen 🌇'},
      {'kind': 'HEALTH_EVENT', 'date': _iso(const Duration(days: 1, hours: 5)), 'postId': null, 'imageUrls': [], 'text': 'Annual checkup — all clear', 'aiLevel': 'GREEN', 'symptomSummary': 'Routine vaccination, healthy'},
      {'kind': 'HAPPY_MOMENT', 'date': _iso(const Duration(days: 2)), 'eventDate': _date(const Duration(days: 2)), 'postId': 101, 'imageUrls': ['${sa}pet07.jpg'], 'text': 'New collar, big round eyes 😺'},
      {'kind': 'HAPPY_MOMENT', 'date': _iso(const Duration(days: 3)), 'eventDate': _date(const Duration(days: 3)), 'postId': 102, 'imageUrls': ['${sa}pet02.jpg', '${sa}pet07.jpg'], 'text': 'Found the little heart on her fur 🩶'},
      {'kind': 'HEALTH_EVENT', 'date': _iso(const Duration(days: 3, hours: 8)), 'postId': null, 'imageUrls': [], 'text': 'Mild sneezing — monitoring', 'aiLevel': 'YELLOW', 'symptomSummary': 'Occasional sneezing, still alert'},
      {'kind': 'HAPPY_MOMENT', 'date': _iso(const Duration(days: 4)), 'eventDate': _date(const Duration(days: 4)), 'postId': 103, 'imageUrls': ['${sa}pet08.jpg'], 'text': 'Fetch champion at the park 🐕'},
      {'kind': 'HAPPY_MOMENT', 'date': _iso(const Duration(days: 5)), 'eventDate': _date(const Duration(days: 5)), 'postId': 105, 'imageUrls': ['${sa}pet04.jpg', '${sa}pet03.jpg'], 'text': 'Two fluffballs, one couch 😹'},
      {'kind': 'HEALTH_EVENT', 'date': _iso(const Duration(days: 6)), 'postId': null, 'imageUrls': [], 'text': 'Ate chocolate — taken to the vet', 'aiLevel': 'RED', 'symptomSummary': 'Vomiting after ingestion'},
      {'kind': 'HAPPY_MOMENT', 'date': _iso(const Duration(days: 7)), 'eventDate': _date(const Duration(days: 7)), 'postId': 106, 'imageUrls': ['${sa}pet05.jpg'], 'text': 'Midnight dance moves 💃'},
      {'kind': 'HAPPY_MOMENT', 'date': _iso(const Duration(days: 8)), 'eventDate': _date(const Duration(days: 8)), 'postId': 107, 'imageUrls': ['${sa}pet10.jpg'], 'text': 'Bubble bath day 🛁'},
      {'kind': 'HEALTH_EVENT', 'date': _iso(const Duration(days: 10)), 'postId': null, 'imageUrls': [], 'text': 'Follow-up — appetite back to normal', 'aiLevel': 'GREEN', 'symptomSummary': 'Recovered, eating well'},
      {'kind': 'HAPPY_MOMENT', 'date': _iso(const Duration(days: 12)), 'eventDate': _date(const Duration(days: 12)), 'postId': 108, 'imageUrls': ['${sa}pet09.jpg'], 'text': 'New bob haircut, very artsy 💇'},
      {'kind': 'HAPPY_MOMENT', 'date': _iso(const Duration(days: 18)), 'eventDate': _date(const Duration(days: 18)), 'postId': 109, 'imageUrls': ['${sa}pet06.jpg'], 'text': 'Sketched my two as frenemies 🎨'},
      {'kind': 'HAPPY_MOMENT', 'date': _iso(const Duration(days: 26)), 'eventDate': _date(const Duration(days: 26)), 'postId': 104, 'imageUrls': ['${sa}pet03.jpg'], 'text': 'His majesty the Maine Coon 😼'},
    ]);
  }

  Map<String, dynamic> _post({
    required int id, required String type, String? body, String? nickname,
    int likeCount = 0, String? imageUrl, List<String>? images, required Duration ago,
  }) {
    final imgs = images ?? (imageUrl == null ? const <String>[] : [imageUrl]);
    return {
      'id': id, 'authorId': 1, 'authorDeleted': false,
      'authorNickname': nickname ?? 'Demo User', 'authorAvatarUrl': null,
      'type': type, 'body': body,
      'firstImageUrl': imgs.isEmpty ? null : imgs.first,
      'imageUrls': imgs, // 详情页轮播全图
      'likeCount': likeCount, 'createdAt': _iso(ago),
    };
  }

  Map<String, dynamic> _comment({
    required int id, required String nickname, required String body, required Duration ago,
  }) => {
        'id': id, 'authorId': 2, 'authorDeleted': false,
        'authorNickname': nickname, 'authorAvatarUrl': null,
        'body': body, 'createdAt': _iso(ago), 'replyCount': 0, 'replies': <dynamic>[],
      };

  Map<String, dynamic> _notif({
    required String token, required String type, String? title, String? body,
    required bool read, required Duration ago,
  }) => {
        'type': type, 'title': title, 'body': body,
        'deepLinkType': null, 'deepLinkToken': null, 'read': read, 'createdAt': _iso(ago),
      }..['_token'] = token;

  Map<String, dynamic> _envelope(List<Map<String, dynamic>> items) =>
      {'items': items, 'nextCursor': null, 'hasMore': false};

  // ---- 里程碑清单 mock（Story 8.2 · FR-42，后端 MilestoneCatalog/MilestoneListResponse 镜像）----
  // 演示态：少数 SYSTEM_AUTO 类标记已完成（彩色徽章），其余灰锁。结构 = L/M/S 三级分区。
  static const Set<String> _milestoneDone = {
    'C-S1', 'C-S2', 'C-S5', 'C-S15', 'D-S1', 'D-S2', 'D-S5', 'D-S15', 'G-S1', 'G-S2', 'G-S5',
  };

  /// 运行时新增完成（如名片分享信号 → C-S3，Story 8.3），演示态可见进度推进。
  final Set<String> _extraMilestoneDone = {};

  /// 已关联里程碑的成长日历内容 id（Story 8.4，打卡选择器置灰用）。
  final Set<int> _linkedContentIds = {};

  /// 单项 [code, level, trigger, title]。
  List<List<String>> _milestoneCatalog(String type) {
    switch (type) {
      case 'DOG':
        return const [
          ['D-S1', 'S', 'SYSTEM_AUTO', '宠物档案创建完成'], ['D-S2', 'S', 'SYSTEM_AUTO', '第一张照片上传到成长日历'],
          ['D-S3', 'S', 'SYSTEM_AUTO', '第一次分享宠物名片'], ['D-S4', 'S', 'SYSTEM_AUTO', '第一次保存兽医问诊结论'],
          ['D-S5', 'S', 'SYSTEM_AUTO', '第一次发布日常分享'], ['D-S6', 'S', 'USER_CHECKIN', '第一次洗澡'],
          ['D-S7', 'S', 'USER_CHECKIN', '第一次美容 / 梳毛'], ['D-S8', 'S', 'USER_CHECKIN', '第一次吃零食'],
          ['D-S9', 'S', 'USER_CHECKIN', '第一次睡在你身边'], ['D-S10', 'S', 'USER_CHECKIN', '第一次摇尾巴'],
          ['D-S11', 'S', 'USER_CHECKIN', '第一次戴项圈 / 牵引绳'], ['D-S12', 'S', 'USER_CHECKIN', '第一次玩球'],
          ['D-S13', 'S', 'USER_CHECKIN', '第一次游泳 / 玩水'], ['D-S14', 'S', 'SYSTEM_AUTO', '第一次被评论'],
          ['D-S15', 'S', 'SYSTEM_AUTO', '第一次收到点赞'],
          ['D-M1', 'M', 'USER_CHECKIN', '第一次出门散步'], ['D-M2', 'M', 'USER_CHECKIN', '第一次坐车'],
          ['D-M3', 'M', 'USER_CHECKIN', '完成第一次疫苗接种'], ['D-M4', 'M', 'USER_CHECKIN', '完成第一次驱虫'],
          ['D-M5', 'M', 'USER_CHECKIN', '第一次看兽医'], ['D-M6', 'M', 'USER_CHECKIN', '第一次见到其他狗狗'],
          ['D-M7', 'M', 'USER_CHECKIN', '学会第一个指令'], ['D-M8', 'M', 'SYSTEM_AUTO', '陪伴满 30 天'],
          ['D-M9', 'M', 'USER_CHECKIN', '完成绝育手术'], ['D-M10', 'M', 'SYSTEM_AUTO', '成长日历记录满 10 条'],
          ['D-L1', 'L', 'PUSH_PUBLISH', '第一个生日 🎂'], ['D-L2', 'L', 'PUSH_PUBLISH', '陪伴满 100 天'],
          ['D-L3', 'L', 'PUSH_PUBLISH', '陪伴满 365 天'], ['D-L4', 'L', 'SYSTEM_AUTO', '完成全部健康里程碑'],
          ['D-L5', 'L', 'SYSTEM_AUTO', '成长日历记录满 30 条'],
        ];
      case 'CAT':
        return const [
          ['C-S1', 'S', 'SYSTEM_AUTO', '宠物档案创建完成'], ['C-S2', 'S', 'SYSTEM_AUTO', '第一张照片上传到成长日历'],
          ['C-S3', 'S', 'SYSTEM_AUTO', '第一次分享宠物名片'], ['C-S4', 'S', 'SYSTEM_AUTO', '第一次保存兽医问诊结论'],
          ['C-S5', 'S', 'SYSTEM_AUTO', '第一次发布日常分享'], ['C-S6', 'S', 'USER_CHECKIN', '第一次洗澡'],
          ['C-S7', 'S', 'USER_CHECKIN', '第一次修剪指甲'], ['C-S8', 'S', 'USER_CHECKIN', '第一次吃零食'],
          ['C-S9', 'S', 'USER_CHECKIN', '第一次睡在你身边'], ['C-S10', 'S', 'USER_CHECKIN', '第一次发出咕噜声'],
          ['C-S11', 'S', 'USER_CHECKIN', '第一次在窗边晒太阳'], ['C-S12', 'S', 'USER_CHECKIN', '第一次玩逗猫棒'],
          ['C-S13', 'S', 'USER_CHECKIN', '第一次钻进纸箱'], ['C-S14', 'S', 'SYSTEM_AUTO', '第一次被评论'],
          ['C-S15', 'S', 'SYSTEM_AUTO', '第一次收到点赞'],
          ['C-M1', 'M', 'USER_CHECKIN', '第一次出门探险'], ['C-M2', 'M', 'USER_CHECKIN', '第一次坐车'],
          ['C-M3', 'M', 'USER_CHECKIN', '完成第一次疫苗接种'], ['C-M4', 'M', 'USER_CHECKIN', '完成第一次驱虫'],
          ['C-M5', 'M', 'USER_CHECKIN', '第一次看兽医'], ['C-M6', 'M', 'USER_CHECKIN', '第一次见到其他猫咪'],
          ['C-M7', 'M', 'USER_CHECKIN', '学会回应自己的名字'], ['C-M8', 'M', 'SYSTEM_AUTO', '陪伴满 30 天'],
          ['C-M9', 'M', 'USER_CHECKIN', '完成绝育手术'], ['C-M10', 'M', 'SYSTEM_AUTO', '成长日历记录满 10 条'],
          ['C-L1', 'L', 'PUSH_PUBLISH', '第一个生日 🎂'], ['C-L2', 'L', 'PUSH_PUBLISH', '陪伴满 100 天'],
          ['C-L3', 'L', 'PUSH_PUBLISH', '陪伴满 365 天'], ['C-L4', 'L', 'SYSTEM_AUTO', '完成全部健康里程碑'],
          ['C-L5', 'L', 'SYSTEM_AUTO', '成长日历记录满 30 条'],
        ];
      default: // OTHER → 通用 15
        return const [
          ['G-S1', 'S', 'SYSTEM_AUTO', '宠物档案创建完成'], ['G-S2', 'S', 'SYSTEM_AUTO', '第一张照片上传到成长日历'],
          ['G-S3', 'S', 'SYSTEM_AUTO', '第一次分享宠物名片'], ['G-S4', 'S', 'SYSTEM_AUTO', '第一次保存兽医问诊结论'],
          ['G-S5', 'S', 'SYSTEM_AUTO', '第一次发布日常分享'], ['G-S6', 'S', 'USER_CHECKIN', '第一次吃零食'],
          ['G-S7', 'S', 'SYSTEM_AUTO', '第一次被评论'], ['G-S8', 'S', 'SYSTEM_AUTO', '第一次收到点赞'],
          ['G-M1', 'M', 'USER_CHECKIN', '第一次看兽医'], ['G-M2', 'M', 'USER_CHECKIN', '完成第一次健康检查 / 疫苗'],
          ['G-M3', 'M', 'SYSTEM_AUTO', '陪伴满 30 天'], ['G-M4', 'M', 'SYSTEM_AUTO', '成长日历记录满 10 条'],
          ['G-L1', 'L', 'PUSH_PUBLISH', '第一个生日 🎂'], ['G-L2', 'L', 'PUSH_PUBLISH', '陪伴满 100 天'],
          ['G-L3', 'L', 'PUSH_PUBLISH', '陪伴满 365 天'],
        ];
    }
  }

  Map<String, dynamic> _milestonePayload(String? petType) {
    final type = petType ?? 'OTHER';
    final catalog = _milestoneCatalog(type);
    Map<String, dynamic> item(List<String> d) {
      final done = _milestoneDone.contains(d[0]) || _extraMilestoneDone.contains(d[0]);
      return {
        'code': d[0], 'title': d[3], 'level': d[1], 'triggerType': d[2], 'completed': done,
        if (done) 'completedAt': _iso(const Duration(days: 5)),
      };
    }

    int totalDone = 0;
    final groups = <Map<String, dynamic>>[];
    for (final lvl in const ['L', 'M', 'S']) {
      final items = catalog.where((d) => d[1] == lvl).map(item).toList();
      final done = items.where((e) => e['completed'] == true).length;
      totalDone += done;
      groups.add({'level': lvl, 'completedCount': done, 'totalCount': items.length, 'items': items});
    }
    return {
      'petName': (_petProfile?['name'] ?? 'Momo') as String,
      // NON_NULL：与后端一致，头像为 null 时省略该键。
      'petAvatarUrl': ?_petProfile?['avatarUrl'],
      'completedCount': totalDone,
      'totalCount': catalog.length,
      'groups': groups,
    };
  }

  // ---- 主入口：返回 Response 或抛 DioException(404) ----
  Response<dynamic>? handle(RequestOptions o) {
    final m = o.method.toUpperCase();
    final p = o.path.replaceFirst(RegExp(r'\?.*$'), '');
    final q = o.queryParameters;
    final body = o.data is Map ? (o.data as Map).cast<String, dynamic>() : <String, dynamic>{};

    Response<dynamic> ok([dynamic data]) =>
        Response<dynamic>(requestOptions: o, statusCode: 200, data: data);
    Response<dynamic> noContent() =>
        Response<dynamic>(requestOptions: o, statusCode: 204, data: null);

    // ---------- AUTH / ME ----------
    if (m == 'POST' && p.endsWith('/auth/google')) {
      return ok({
        'accessToken': 'mock-access', 'refreshToken': 'mock-refresh',
        'role': 'USER', 'isNewUser': true, 'onboardingCompleted': false,
        'profile': _profile,
      });
    }
    if (m == 'POST' && p.endsWith('/auth/refresh')) {
      return ok({'accessToken': 'mock-access2', 'refreshToken': 'mock-refresh2'});
    }
    if (m == 'POST' && (p.endsWith('/auth/logout') || p.endsWith('/vet/logout'))) return noContent();
    if (m == 'POST' && p.endsWith('/auth/vet/login')) {
      return ok({'accessToken': 'mock-vet', 'refreshToken': 'mock-vet-r', 'displayName': 'Drh. Demo', 'role': 'VET'});
    }
    // 精确匹配用户 /api/v1/me(用 /v1/me 收尾,避免误吃 /pet-profiles/me、/vet/me)。
    if (p.endsWith('/v1/me') && m == 'GET') return ok(_profile);
    if (p.endsWith('/v1/me') && m == 'PATCH') {
      if (body['nickname'] != null) _profile['nickname'] = body['nickname'];
      if (body['petStatus'] != null) _profile['petStatus'] = body['petStatus'];
      return ok(_profile);
    }
    if (p.endsWith('/v1/me') && m == 'DELETE') return Response(requestOptions: o, statusCode: 202);
    if (p.endsWith('/vet/me') && m == 'GET') return ok({'id': 1, 'displayName': 'Drh. Demo', 'status': 'ACTIVE'});

    // ---------- CONTENT / FEED ----------
    if (m == 'GET' && p.endsWith('/me/posts')) {
      return ok(_envelope(_feed
          .where((e) => e['authorId'] == 1)
          .map((e) => {'id': e['id'], 'type': e['type'], 'body': e['body'], 'firstImageUrl': e['firstImageUrl']})
          .toList()));
    }
    if (m == 'GET' && p.endsWith('/content-posts')) {
      final cat = (q['category'] ?? 'ALL') as String;
      final items = cat == 'ALL' ? _feed : _feed.where((e) => e['type'] == cat).toList();
      // 投影为卡片 10 字段契约：内部 imageUrls（详情多图）不进 Feed 信封。
      return ok(_envelope(items.map((e) {
        final card = Map<String, dynamic>.from(e)..remove('imageUrls');
        return card;
      }).toList()));
    }
    if (m == 'POST' && p.endsWith('/content-posts')) {
      // 镜像后端发布时三方自动审核（AC8 · F10）：占位关键词/图标记命中 → 422，不落库。
      final text = body['text'] as String?;
      final imgs = (body['imageUrls'] as List?)?.cast<dynamic>() ?? const [];
      // 占位敏感词（印尼语/英语，与后端 ContentModerationService 对齐）；真实三方后接。
      const blockedWords = ['judi', 'narkoba', 'pornografi', 'penipuan', 'gambling', 'scam'];
      final lower = (text ?? '').toLowerCase();
      if (blockedWords.any(lower.contains)) {
        throw _problem(o, 422, 'content-text-blocked', 'Konten mengandung kata tidak pantas');
      }
      if (imgs.any((u) => u.toString().contains('moderation-blocked'))) {
        throw _problem(o, 422, 'content-image-blocked', 'Gambar mengandung konten terlarang');
      }
      final id = _nextId();
      // 发布回灌：把刚选/拍的图（mock 上传返回的 file: URL）带进新帖，Feed/详情即时显示真图。
      _feed.insert(0, _post(id: id, type: (body['type'] ?? 'DAILY') as String,
          body: text, likeCount: 0, images: imgs.map((e) => e.toString()).toList(),
          ago: Duration.zero));
      return ok({'id': id});
    }
    final detailMatch = RegExp(r'/content-posts/(\d+)$').firstMatch(p);
    if (detailMatch != null && m == 'GET') {
      final id = int.parse(detailMatch.group(1)!);
      final post = _feed.firstWhere((e) => e['id'] == id, orElse: () => <String, dynamic>{});
      if (post.isEmpty) throw _notFound(o);
      return ok({
        ...post, 'commentCount': (_comments[id]?.length ?? 0), 'liked': false,
        'isAuthor': post['authorId'] == 1,
        // 详情轮播取全图列表（多图帖展示多张）；回退 firstImageUrl。
        'imageUrls': (post['imageUrls'] as List?)?.isNotEmpty == true
            ? post['imageUrls']
            : (post['firstImageUrl'] == null ? [] : [post['firstImageUrl']]),
      });
    }
    if (detailMatch != null && m == 'DELETE') {
      _feed.removeWhere((e) => e['id'] == int.parse(detailMatch.group(1)!));
      return ok();
    }
    final commentsMatch = RegExp(r'/content-posts/(\d+)/comments$').firstMatch(p);
    if (commentsMatch != null) {
      final id = int.parse(commentsMatch.group(1)!);
      if (m == 'GET') return ok(_envelope(_comments[id] ?? []));
      if (m == 'POST') {
        final c = _comment(id: _nextId(), nickname: _profile['nickname'] as String,
            body: (body['body'] ?? '') as String, ago: Duration.zero);
        (_comments[id] ??= []).insert(0, c);
        return ok(c);
      }
    }
    final repliesMatch = RegExp(r'/comments/(\d+)/replies$').firstMatch(p);
    if (repliesMatch != null) {
      final parentId = int.parse(repliesMatch.group(1)!);
      if (m == 'GET') return ok(_envelope(_replies[parentId] ?? []));
      if (m == 'POST') {
        final r = {'id': _nextId(), 'authorId': 1, 'authorDeleted': false,
          'authorNickname': _profile['nickname'], 'authorAvatarUrl': null,
          'body': body['body'] ?? '', 'createdAt': _iso(Duration.zero), 'replyCount': null, 'replies': null};
        (_replies[parentId] ??= []).add(r);
        return ok(r);
      }
    }
    if (RegExp(r'/comments/(\d+)$').hasMatch(p) && m == 'DELETE') return ok();
    final likeMatch = RegExp(r'/content-posts/(\d+)/like$').firstMatch(p);
    if (likeMatch != null) {
      final id = int.parse(likeMatch.group(1)!);
      final post = _feed.firstWhere((e) => e['id'] == id, orElse: () => <String, dynamic>{});
      final liked = m == 'POST';
      if (post.isNotEmpty) post['likeCount'] = (post['likeCount'] as int) + (liked ? 1 : -1);
      return ok({'liked': liked, 'likeCount': post['likeCount'] ?? 0});
    }
    if (RegExp(r'/content-posts/(\d+)/reports$').hasMatch(p)) return ok();
    final miniMatch = RegExp(r'/users/(\d+)/mini-profile$').firstMatch(p);
    if (miniMatch != null) {
      // 按 userId 投影不同昵称/发布数，迷你卡不再千篇一律。
      final uid = int.parse(miniMatch.group(1)!);
      const names = ['Putri', 'Sari', 'Budi', 'Andi', 'Maya', 'Dewi', 'Joko', 'Rina'];
      const avatars = ['pet07', 'pet02', 'pet08', 'pet03', 'pet04', 'pet05', 'pet09', 'pet06'];
      final i = uid % names.length;
      return ok({'postCount': 3 + uid % 9, 'isDeactivated': false,
        'nickname': names[i], 'avatarUrl': 'asset:assets/seed/${avatars[i]}.jpg'});
    }

    // ---------- PET PROFILE / TIMELINE / HEALTH ----------
    if (p.endsWith('/pet-profiles/me/timeline') && m == 'GET') return ok(_envelope(_timeline));
    // 里程碑列表/进度（Story 8.2 · FR-42，后端 MilestoneListResponse 镜像）。
    if (p.endsWith('/pet-profiles/me/milestones') && m == 'GET') {
      if (_petProfile == null) throw _notFound(o);
      return ok(_milestonePayload(_petProfile!['petType'] as String?));
    }
    // 用户打卡候选（Story 8.4）：本人成长日历内容，已关联的标 linked。
    if (p.endsWith('/pet-profiles/me/milestones/checkin-candidates') && m == 'GET') {
      if (_petProfile == null) throw _notFound(o);
      final items = _timeline
          .where((e) => e['kind'] == 'HAPPY_MOMENT' && e['postId'] != null)
          .map((e) => {
                'contentId': e['postId'],
                if (e['imageUrls'] is List && (e['imageUrls'] as List).isNotEmpty)
                  'firstImageUrl': (e['imageUrls'] as List).first,
                if (e['eventDate'] != null) 'eventDate': e['eventDate'],
                if (e['text'] != null) 'text': e['text'],
                'linked': _linkedContentIds.contains(e['postId']),
              })
          .toList();
      return ok({'items': items});
    }
    // 用户打卡（Story 8.4）：关联一条成长日历内容并完成里程碑。
    if (p.contains('/pet-profiles/me/milestones/') && p.endsWith('/check-in') && m == 'POST') {
      if (_petProfile == null) throw _notFound(o);
      final code = p.split('/milestones/')[1].split('/check-in')[0];
      final contentId = body['contentId'];
      _extraMilestoneDone.add(code);
      if (contentId != null) _linkedContentIds.add(contentId as int);
      final def = _milestoneCatalog((_petProfile!['petType'] as String?) ?? 'OTHER')
          .firstWhere((d) => d[0] == code, orElse: () => [code, 'S', 'USER_CHECKIN', code]);
      return ok({
        'code': code, 'title': def[3], 'level': def[1], 'triggerType': def[2],
        'completed': true, 'completedAt': _iso(Duration.zero),
      });
    }
    // 名片分享信号 → C-S3 自动完成（Story 8.3）。204，幂等。
    if (p.endsWith('/pet-profiles/me/card-shares') && m == 'POST') {
      if (_petProfile == null) throw _notFound(o);
      final prefix = switch (_petProfile!['petType'] as String?) {
        'DOG' => 'D', 'CAT' => 'C', _ => 'G',
      };
      _extraMilestoneDone.add('$prefix-S3');
      return ok();
    }
    if (p.endsWith('/pet-profiles/me/archive-stats') && m == 'GET') {
      if (_petProfile == null) throw _notFound(o);
      final happy = _timeline.where((e) => e['kind'] == 'HAPPY_MOMENT').length;
      final consult = _timeline.where((e) => e['kind'] == 'HEALTH_EVENT').length;
      final ms = _milestonePayload(_petProfile!['petType'] as String?);
      return ok({
        'happyMomentCount': happy, 'consultCount': consult,
        // 里程碑统计与 /me/milestones 同源真计数（连带 2-4 真供数，AC5）。
        'milestoneCompleted': ms['completedCount'], 'milestoneTotal': ms['totalCount'],
      });
    }
    if (p.endsWith('/pet-profiles/me/calendar') && m == 'GET') {
      if (_petProfile == null) throw _notFound(o);
      final year = int.tryParse('${q['year']}') ?? DateTime.now().year;
      final month = int.tryParse('${q['month']}') ?? DateTime.now().month;
      return ok(_calendarFor(year, month));
    }
    if (p.endsWith('/pet-profiles/me/day') && m == 'GET') {
      if (_petProfile == null) throw _notFound(o);
      final date = (q['date'] ?? '') as String;
      final items = _timeline.where((e) => _itemDateKey(e) == date).toList()
        ..sort((a, b) => (a['date'] as String).compareTo(b['date'] as String)); // created_at 正序
      return ok({'date': date, 'items': items});
    }
    if (p.endsWith('/pet-profiles/me') && m == 'GET') {
      if (_petProfile == null) throw _notFound(o);
      return ok(_petProfile);
    }
    if (p.endsWith('/pet-profiles/me') && m == 'PATCH') {
      _petProfile = {...?_petProfile, ...body};
      return ok(_petProfile);
    }
    if (p.endsWith('/pet-profiles') && m == 'POST') {
      _petProfile = {'id': _nextId(), 'cardToken': 'mock-card-${_nextId()}', ...body, 'createdAt': _iso(Duration.zero)};
      _profile['hasPetProfile'] = true;
      return ok(_petProfile);
    }
    if (p.endsWith('/health-events/archive-decisions') && m == 'POST') {
      if (body['decision'] == 'ARCHIVED') {
        _timeline.insert(0, {'kind': 'HEALTH_EVENT', 'date': _iso(Duration.zero), 'postId': null,
          'imageUrls': [], 'text': body['symptomSummary'] ?? 'Health event', 'aiLevel': body['aiLevel'], 'symptomSummary': body['symptomSummary']});
      }
      return ok();
    }
    if (p.endsWith('/health-events/decision') && m == 'GET') return ok({'decided': false});

    // ---------- AI 分诊 ----------
    if (p.endsWith('/triage') && m == 'POST') {
      final id = _nextId();
      _triageLevel[id] = _triageCycle[_triageSeq++ % _triageCycle.length]; // 轮流绿/黄/红
      return Response(requestOptions: o, statusCode: 202, data: {'triageId': id});
    }
    final triageGet = RegExp(r'/triage/(\d+)$').firstMatch(p);
    if (triageGet != null && m == 'GET') {
      final id = int.parse(triageGet.group(1)!);
      final lvl = _triageLevel[id] ?? 'GREEN';
      return ok({
        'status': 'DONE', 'dangerLevel': lvl,
        'advice': lvl == 'RED' ? 'Seek veterinary care immediately' : (lvl == 'YELLOW' ? 'Monitor closely and consult a vet soon' : 'No obvious risk for now — keep an eye on it'),
        'medicationRef': null, 'disclaimer': 'This result is for reference only and does not replace professional veterinary diagnosis.',
        'observation': {'indicators': ['Alertness', 'Appetite', 'Bowel movements'], 'timeWindow': '24 hours', 'escalationTriggers': ['Persistent vomiting', 'Lethargy']},
      });
    }

    // ---------- 兽医咨询(用户侧) ----------
    // expectedWindow 是后端文案 key（前端映射 l10n），非渲染文本 —— 镜像 ConsultAvailabilityResponse.DEFAULT_WINDOW_KEY。
    if (p.endsWith('/consult/availability') && m == 'GET') return ok({'vetOnline': true, 'expectedWindow': 'WEEKDAY_8_23'});
    if (p.endsWith('/consult-sessions/active') && m == 'GET') {
      return _activeSession == null ? noContent() : ok(_activeSession);
    }
    if (p.endsWith('/consult-sessions/pending-rating') && m == 'GET') return noContent();
    if (p.endsWith('/consult-sessions') && m == 'POST') {
      _activeSession = {'id': _nextId(), 'status': 'WAITING', 'source': body['source'] ?? 'DIRECT',
        'vetId': null, 'waitingElapsedSeconds': 0, 'timedOut': false, 'alreadyActive': false,
        'closedReason': null, 'interruptedReason': null};
      return ok(_activeSession);
    }
    // 用户侧 consult-sessions/{id}:用 !/vet/ 守卫,避免误吞兽医侧 /vet/consult-sessions/{id}。
    final sessGet = RegExp(r'/consult-sessions/(\d+)$').firstMatch(p);
    if (sessGet != null && m == 'GET' && !p.contains('/vet/')) {
      return ok(_activeSession ?? {'id': int.parse(sessGet.group(1)!), 'status': 'IN_PROGRESS', 'source': 'DIRECT',
        'waitingElapsedSeconds': 0, 'timedOut': false, 'alreadyActive': false});
    }
    if (RegExp(r'/consult-sessions/(\d+)/continue-waiting$').hasMatch(p)) {
      _activeSession?['waitingElapsedSeconds'] = 0;
      return ok(_activeSession ?? {});
    }
    if (RegExp(r'/consult-sessions/(\d+)$').hasMatch(p) && m == 'DELETE' && !p.contains('/vet/')) {
      final s = {...?_activeSession, 'status': 'CANCELLED'};
      _activeSession = null;
      return ok(s);
    }
    if (RegExp(r'/consult-sessions/(\d+)/rating$').hasMatch(p)) {
      _activeSession = null;
      return ok({'id': 1, 'status': 'CLOSED', 'closedReason': 'RATED', 'source': 'DIRECT', 'waitingElapsedSeconds': 0, 'timedOut': false, 'alreadyActive': false});
    }
    if (RegExp(r'/consult-sessions/(\d+)/rating-prompted$').hasMatch(p)) return ok();
    if (p.endsWith('/consult/history') && m == 'GET') return ok(_envelope(_consultHistory));

    // ---------- 兽医工作台 ----------
    // 镜像 OnlineStatusResponse{online, status}：status 是 VetPresenceStatus 名（ONLINE/OFFLINE），App 暂只读 online 但 mock 须齐全。
    if (p.endsWith('/vet/online-status') && m == 'GET') return ok({'online': true, 'status': 'ONLINE'});
    if (p.endsWith('/vet/online-status') && m == 'PUT') {
      final online = (body['online'] ?? true) as bool;
      return ok({'online': online, 'status': online ? 'ONLINE' : 'OFFLINE'});
    }
    if (p.endsWith('/vet/heartbeat') && m == 'POST') return ok();
    if (p.endsWith('/vet/consult-sessions/waiting') && m == 'GET') {
      return ok([
        {'sessionId': 8100, 'source': 'AI_UPGRADE', 'aiDangerLevel': 'RED', 'symptomPreview': 'Makan cokelat lalu muntah berulang & lemas drastis sejak pagi', 'imageCount': 3, 'waitingElapsedSeconds': 50,
          'petName': 'Benji', 'petSpecies': 'DOG', 'petSex': 'MALE', 'petAgeMonths': 60, 'ownerHandle': 'bagas'},
        {'sessionId': 8101, 'source': 'AI_UPGRADE', 'aiDangerLevel': 'YELLOW', 'symptomPreview': 'Muntah busa putih 2x semalam, jadi lebih lemas & kurang nafsu makan', 'imageCount': 2, 'waitingElapsedSeconds': 45,
          'petName': 'Oyen', 'petSpecies': 'CAT', 'petSex': 'MALE', 'petAgeMonths': 24, 'ownerHandle': 'rani'},
        {'sessionId': 8102, 'source': 'AI_UPGRADE', 'aiDangerLevel': 'GREEN', 'symptomPreview': 'Bersin-bersin sejak 2 hari, makan & main masih normal', 'imageCount': 1, 'waitingElapsedSeconds': 90,
          'petName': 'Bruno', 'petSpecies': 'DOG', 'petSex': 'MALE', 'petAgeMonths': 36, 'ownerHandle': 'dimas'},
        {'sessionId': 8103, 'source': 'DIRECT', 'aiDangerLevel': null, 'symptomPreview': null, 'imageCount': 0, 'waitingElapsedSeconds': 150,
          'petName': 'Mochi', 'petSpecies': 'CAT', 'petSex': 'FEMALE', 'petAgeMonths': 8, 'ownerHandle': 'aditya'},
      ]);
    }
    // 「进行中」会话列表（工作台 Active Tab）。点卡 → /vet/conversation/:id（IM 占位聊天）。
    if (p.endsWith('/vet/consult-sessions/in-progress') && m == 'GET') {
      return ok([
        {'sessionId': 8001, 'source': 'AI_UPGRADE', 'petName': 'Oyen', 'unread': 1,
          'lastMessage': 'Minum masih mau sedikit, makan belum mau. Tadi sempat gigit tali tirai 😅'},
        {'sessionId': 8002, 'source': 'DIRECT', 'petName': 'Milo', 'unread': 0,
          'lastMessage': 'Oke dok, nanti saya pantau lagi 2 jam ke depan 🙏'},
      ]);
    }
    // 已结束「历史」列表（工作台 History Tab）。
    if (p.endsWith('/vet/consult-sessions/history') && m == 'GET') {
      return ok([
        {'sessionId': 7901, 'petName': 'Cookie', 'date': _iso(const Duration(days: 1)), 'stars': 5,
          'terminalState': 'CLOSED', 'summary': 'Diare ringan — disarankan diet hambar & oralit, pantau 24 jam'},
        {'sessionId': 7902, 'petName': 'Luna', 'date': _iso(const Duration(days: 3)), 'stars': 4,
          'terminalState': 'CLOSED', 'summary': 'Gatal & garuk telinga — dugaan ear mites, rujuk klinik untuk cek mikroskop'},
        {'sessionId': 7903, 'petName': 'Tofu', 'date': _iso(const Duration(days: 6)), 'stars': null,
          'terminalState': 'INTERRUPTED', 'summary': 'Sesi terputus sebelum selesai (user keluar)'},
      ]);
    }
    final vetAccept = RegExp(r'/vet/consult-sessions/(\d+)/accept$').firstMatch(p);
    if (vetAccept != null && m == 'POST') {
      final id = int.parse(vetAccept.group(1)!);
      _vetAccepted.add(id); // 接单成功：转 IN_PROGRESS（后续 GET 不再回 WAITING）
      return ok(_vetSessionView(id, 'IN_PROGRESS'));
    }
    final vetGet = RegExp(r'/vet/consult-sessions/(\d+)$').firstMatch(p);
    if (vetGet != null && m == 'GET') {
      final id = int.parse(vetGet.group(1)!);
      // 待接单池且未接单 → WAITING（请求预览页轮询见 WAITING 才不会判「已被抢」弹出）。
      final waiting = _vetWaitingPool.contains(id) && !_vetAccepted.contains(id);
      return ok(_vetSessionView(id, waiting ? 'WAITING' : 'IN_PROGRESS'));
    }
    final aiCtx = RegExp(r'/vet/consult-sessions/(\d+)/ai-context$').firstMatch(p);
    if (aiCtx != null && m == 'GET') {
      final id = int.parse(aiCtx.group(1)!);
      // DIRECT 会话无 AI 分诊上下文 → 会话页不渲染 AI 卡。
      if ((_vetSessionSource[id] ?? 'DIRECT') == 'DIRECT') {
        return ok({'hasAiContext': false, 'dangerLevel': null, 'symptomText': null, 'imageUrls': []});
      }
      return ok({'hasAiContext': true, 'dangerLevel': 'YELLOW', 'symptomText': 'Kucing (Oyen, 2th) muntah busa putih 2x semalam, nafsu makan turun & lebih lemas. Riwayat gigit tali tirai.', 'imageUrls': []});
    }
    if (RegExp(r'/vet/consult-sessions/(\d+)/assist$').hasMatch(p)) {
      return ok({'aiReferenceReply': 'Puasakan makanan 2-3 jam, tetap sediakan air sedikit tapi sering, lalu coba makanan hambar porsi kecil. Pantau 24 jam; bila muntah >3x, ada darah, atau makin lemas segera ke klinik.', 'historySummaries': []});
    }
    if (RegExp(r'/vet/consult-sessions/(\d+)/end$').hasMatch(p)) {
      return ok({'id': 1, 'status': 'PENDING_CLOSE', 'source': 'DIRECT', 'userId': 1, 'imConversationId': 'mock-im', 'hasAiContext': true});
    }
    if (RegExp(r'/vet/consult-sessions/(\d+)/notify-reply$').hasMatch(p)) return ok();

    // ---------- 通知 / 版本 / 媒体 ----------
    if (p.endsWith('/notifications') && m == 'GET') return ok(_envelope(_notifications));
    if (p.endsWith('/notifications/unread-count') && m == 'GET') {
      return ok({'count': _notifications.where((e) => e['read'] == false).length});
    }
    if (p.endsWith('/notifications/read-all') && m == 'POST') {
      for (final n in _notifications) {
        n['read'] = true;
      }
      return ok();
    }
    final notifRead = RegExp(r'/notifications/([^/]+)/read$').firstMatch(p);
    if (notifRead != null && m == 'POST') {
      final tok = notifRead.group(1);
      for (final n in _notifications) {
        if (n['_token'] == tok) n['read'] = true;
      }
      return ok();
    }
    if (p.endsWith('/app-version') && m == 'GET') {
      return ok({'latestVersion': '1.0.0', 'minSupportedVersion': '1.0.0', 'iosStoreUrl': null, 'androidStoreUrl': null});
    }
    if (p.endsWith('/media/sts-credentials') && m == 'POST') {
      return ok({'accessKeyId': 'mock', 'accessKeySecret': 'mock', 'securityToken': 'mock',
        'expiration': _iso(const Duration(hours: -1)), 'bucket': 'mock-bucket', 'region': 'mock',
        'endpoint': 'https://mock.example', 'uploadDir': 'mock/', 'cdnBaseUrl': 'https://mock.example'});
    }

    // 未覆盖端点：返回合理空成功(避免触网/崩溃),并 warn。
    if (kDebugMode) debugPrint('[MOCK] 未覆盖端点 → 空成功: $m $p');
    if (m == 'GET') return ok(_envelope([]));
    return ok({});
  }

  /// 兽医侧会话视图 JSON（source/hasAiContext 按 demo 元数据映射；DIRECT 无 AI 上下文卡）。
  Map<String, dynamic> _vetSessionView(int id, String status) {
    final source = _vetSessionSource[id] ?? 'DIRECT';
    final pet = _vetSessionPet[id] ?? _vetSessionPet[8101]!; // 未命中取 Oyen 兜底（dev 深链）
    return {
      'id': id, 'status': status, 'source': source, 'userId': 1,
      'imConversationId': 'mock-im-$id', 'hasAiContext': source == 'AI_UPGRADE',
      ...pet,
    };
  }

  /// 条目的事件日期键（yyyy-MM-dd）：快乐时刻取 eventDate，健康事件取 date 的日期段。
  String _itemDateKey(Map<String, dynamic> e) {
    final ev = e['eventDate'] as String?;
    if (ev != null) return ev;
    return (e['date'] as String).substring(0, 10);
  }

  /// 按年月聚合 _timeline → 日历有记录日格子（镜像后端 /me/calendar）。
  Map<String, dynamic> _calendarFor(int year, int month) {
    final prefix = '$year-${month.toString().padLeft(2, '0')}-';
    final byDay = <int, Map<String, dynamic>>{};
    for (final e in _timeline) {
      final key = _itemDateKey(e);
      if (!key.startsWith(prefix)) continue;
      final day = int.parse(key.substring(8, 10));
      final happy = e['kind'] == 'HAPPY_MOMENT';
      final health = e['kind'] == 'HEALTH_EVENT';
      final cell = byDay[day] ??=
          {'day': day, 'firstImageUrl': null, 'hasHappyMoment': false, 'hasHealthEvent': false};
      if (happy) {
        cell['hasHappyMoment'] = true;
        final imgs = (e['imageUrls'] as List?) ?? const [];
        if (cell['firstImageUrl'] == null && imgs.isNotEmpty) cell['firstImageUrl'] = imgs.first;
      }
      if (health) cell['hasHealthEvent'] = true;
    }
    final days = byDay.values.toList()..sort((a, b) => (a['day'] as int).compareTo(b['day'] as int));
    return {'year': year, 'month': month, 'days': days};
  }

  DioException _notFound(RequestOptions o) => DioException(
        requestOptions: o,
        response: Response(requestOptions: o, statusCode: 404),
        type: DioExceptionType.badResponse,
      );

  /// 构造 RFC 9457 ProblemDetail 错误（mock 镜像后端语义，便于离线演示拦截/校验态）。
  DioException _problem(RequestOptions o, int status, String typeSlug, String detail) =>
      DioException(
        requestOptions: o,
        response: Response(requestOptions: o, statusCode: status, data: {
          'type': 'https://petgo/errors/$typeSlug',
          'status': status,
          'detail': detail,
        }),
        type: DioExceptionType.badResponse,
      );
}
