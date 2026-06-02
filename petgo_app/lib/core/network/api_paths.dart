/// 后端 API 路径常量（`/api/v1`）。集中管理，禁止散落字符串。
class ApiPaths {
  ApiPaths._();

  static const String base = '/api/v1';

  static const String authGoogle = '$base/auth/google';
  static const String authRefresh = '$base/auth/refresh';

  /// 兽医账密登录（Story 5.1）。签发 role=VET JWT，与用户侧 Google 流程隔离。
  static const String authVetLogin = '$base/auth/vet/login';

  /// 兽医自身视图（Story 5.1 登录后探活 + 工作台顶部展示）。
  static const String vetMe = '$base/vet/me';

  /// 兽医侧会话 AI 上下文（Story 5.4，含私密图签名 URL）。
  static String vetConsultAiContext(int sessionId) =>
      '$base/vet/consult-sessions/$sessionId/ai-context';

  /// 兽医侧待接单/接单/会话/辅助（Story 5.5）。
  static const String vetConsultWaiting = '$base/vet/consult-sessions/waiting';
  static String vetConsultAccept(int sessionId) => '$base/vet/consult-sessions/$sessionId/accept';
  static String vetConsultSession(int sessionId) => '$base/vet/consult-sessions/$sessionId';
  static String vetConsultAssist(int sessionId) => '$base/vet/consult-sessions/$sessionId/assist';

  /// IM UserSig 签发（Story 5.5，客户端 SDK 登录用）。
  static const String imUserSig = '$base/im/usersig';

  /// 兽医在线态读写 + 心跳 + 登出（Story 5.2）。
  static const String vetOnlineStatus = '$base/vet/online-status';
  static const String vetHeartbeat = '$base/vet/heartbeat';
  static const String vetLogout = '$base/vet/logout';

  /// 用户侧兽医咨询可用性（Story 5.2，只回 vetOnline bool）。
  static const String consultAvailability = '$base/consult/availability';

  /// 咨询会话（Story 5.3）。POST 发起 / GET 轮询 / DELETE 取消。
  static const String consultSessions = '$base/consult-sessions';
  static const String consultSessionActive = '$base/consult-sessions/active';
  static String consultSession(int id) => '$base/consult-sessions/$id';
  static String consultSessionContinueWaiting(int id) =>
      '$base/consult-sessions/$id/continue-waiting';

  /// 评分门（Story 5.6）。POST 评分 / PATCH 补弹已展示 / GET 待补弹。
  static String consultSessionRating(int id) => '$base/consult-sessions/$id/rating';
  static String consultSessionRatingPrompted(int id) => '$base/consult-sessions/$id/rating-prompted';
  static const String consultPendingRating = '$base/consult-sessions/pending-rating';

  /// 兽医结束会话（Story 5.6）。
  static String vetConsultEnd(int sessionId) => '$base/vet/consult-sessions/$sessionId/end';

  /// 兽医回复后通知用户（Story 6.2，FR-22A）。发完 IM 消息 ping 触发用户推送。
  static String vetConsultNotifyReply(int sessionId) =>
      '$base/vet/consult-sessions/$sessionId/notify-reply';

  /// 问诊历史聚合（Story 5.8，AI + 兽医两类，游标分页）。
  static const String consultHistory = '$base/consult/history';

  /// 当前用户主体统一端点（决策 C1：全平台用 /me，不用 /users/me）。
  static const String me = '$base/me';

  /// 媒体 STS 上传凭证（Story 2.1）。
  static const String mediaStsCredentials = '$base/media/sts-credentials';

  /// 宠物档案（Story 2.2）。
  static const String petProfiles = '$base/pet-profiles';
  static const String petProfileMe = '$base/pet-profiles/me';

  /// 成长时间线（Story 2.4）。
  static const String petProfileTimeline = '$base/pet-profiles/me/timeline';

  /// 内容发布 + Feed 列表（Story 2.3 / 3.2）。
  static const String contentPosts = '$base/content-posts';

  /// 内容详情（Story 3.3）。
  static String contentPostDetail(int id) => '$base/content-posts/$id';

  /// 内容一级评论分页（Story 3.3）。
  static String contentPostComments(int id) => '$base/content-posts/$id/comments';

  /// 某一级评论的二级回复展开（Story 3.3）。
  static String commentReplies(int parentId) => '$base/comments/$parentId/replies';

  /// 内容点赞开关（Story 3.4）。POST 点赞 / DELETE 取消。
  static String contentPostLike(int id) => '$base/content-posts/$id/like';

  /// 他人迷你主页投影（Story 3.8）。
  static String userMiniProfile(int userId) => '$base/users/$userId/mini-profile';

  /// 问诊存档（Story 2.5）。
  static const String healthArchiveDecisions = '$base/health-events/archive-decisions';
  static const String healthDecision = '$base/health-events/decision';

  /// AI 智能分诊（Story 4.1）。POST 受理（202+triageId）/ GET 短轮询取结果。
  static const String triage = '$base/triage';
  static String triageResult(int id) => '$base/triage/$id';
}
