package com.tailtopia.shared.media;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * OSS 个人图生命周期终点（Story 7.3 原为注销级联物理删除）。
 *
 * <p><b>2026-08-19 业务拍板（CROSS-STORY-DECISIONS F21，KOL KTP 快照头像丢失事故）：OSS 对象任何情况
 * 不再物理删除。</b>{@code id_cards} 等快照会长期引用档案头像对象，物理删把快照打成死链且不可恢复
 * （桶未开版本控制，9 卡已永久损失）。本服务保留 API 形状供级联编排（注销 7.3 / 档案单删 F18）继续调用，
 * 但只记账不删对象——DB 行删除/匿名化不受影响，仅对象存储保留。属对 D2「私密桶副本随个人图删除」的
 * 有意偏离，风险由业务负责人确认承担（详见 F21）。新增删除逻辑前必须先回 F21 重新拍板。
 */
@Service
public class MediaDeletionService {

    private static final Logger log = LoggerFactory.getLogger(MediaDeletionService.class);

    /** 私密桶②对象 key 列表：只记账保留，不物理删除（F21）。 */
    public void deletePrivateKeys(List<String> keys) {
        if (keys == null) {
            return;
        }
        log.info("注销私密图保留不删（F21） count={}", keys.size());
    }

    /** 公开桶①个人图 URL 列表：只记账保留，不物理删除（F21）。 */
    public void deletePublicByUrls(List<String> urls) {
        if (urls == null) {
            return;
        }
        log.info("注销公开个人图保留不删（F21） count={}", urls.size());
    }
}
