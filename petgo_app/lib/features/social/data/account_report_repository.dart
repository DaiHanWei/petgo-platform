import 'package:dio/dio.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/network/api_paths.dart';
import '../../../core/network/dio_client.dart';
import '../domain/account_report_reason.dart';

/// 账号举报数据层（V1.1.4 FR-58）。服务端契约见 Story 2.1：`POST /api/v1/account-reports` → 204。
///
/// 401 由 `AuthInterceptor` 统一处理，本层不自理；其余错误原样以 `DioException` 抛给调用方
/// （抽屉据此走失败态：不清空已选、给提示、可直接重试）。
abstract class AccountReportRepository {
  /// 举报一个账号。[detail] 仅在 [AccountReportReason.other] 时需要（≤200 字，服务端权威）。
  Future<void> report(int targetUserId, AccountReportReason reason, {String? detail});
}

class DioAccountReportRepository implements AccountReportRepository {
  DioAccountReportRepository(this.dio);

  final Dio dio;

  @override
  Future<void> report(int targetUserId, AccountReportReason reason, {String? detail}) async {
    // 204 No Content，无响应体——举报不回显任何工单信息（对被举报人不可见，对举报人也不下发内部数据）。
    await dio.post<void>(ApiPaths.accountReports, data: <String, dynamic>{
      'targetUserId': targetUserId,
      'reason': reason.wire,
      if (detail != null && detail.trim().isNotEmpty) 'detail': detail.trim(),
    });
  }
}

final Provider<AccountReportRepository> accountReportRepositoryProvider =
    Provider<AccountReportRepository>((ref) => DioAccountReportRepository(ref.read(dioProvider)));
