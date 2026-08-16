package com.tailtopia.social.read;

/**
 * 隐藏关系<b>只读端口</b>（Story 1.1，FR-94 / FR-58）—— {@code social} 模块对外的<b>唯一</b>出口。
 *
 * <p><b>⚠️ 安全规则层：只升不降、不可绕过（架构 S1）。</b>
 * <b>凡是向用户展示他人内容的位置，一律套用同一层隐藏过滤，不做逐场景例外。</b>
 * 新增任何「展示他人内容」的列表 / 详情 / 推荐位时，<b>默认套用本端口</b>；
 * 若某个运营干预位（顶置位、推荐位等）命中被隐藏作者，则对该用户<b>视为该位为空</b>，
 * 按该功能自身已定义的「位为空」回退逻辑处理 —— 本端口不为其选替补内容。
 * <b>漏一处等于拉黑白拉。</b>
 *
 * <p><b>为什么必须提供两种查询：</b>六处生效点里<b>只有「主页访问」区分来源</b>——它只认主动拉黑，
 * 举报隐藏照常放行（那是 FR-58 闭环的支点：「已举报」状态与重复举报入口都靠它，AD-11）。
 * 若本端口只暴露 {@link #isHidden}，研发就会拿它去拦主页，<b>一并把举报隐藏拦掉，直接打死 FR-58 的闭环</b>。
 *
 * <p><b>模块边界（AD-8）</b>：{@code content} / {@code notify} / {@code auth} 三侧
 * <b>只依赖本接口，禁止引用 {@code social.repository}</b>。
 * 本接口放在<b>提供方</b>而非消费方 —— 有意偏离既有 {@code ViolationCountReader} 的先例
 * （那条只有一个消费方；本端口有三个，照搬会产出三份同义接口）。
 *
 * <p><b>无缓存</b>：每次查库、走唯一索引 {@code uq_user_hide_relations_holder_target_source}
 * 与查询索引 {@code idx_user_hide_relations_holder_target}。代价与既有举报过滤子查询同量级，
 * <b>禁止为其引入 Redis 或本地缓存</b>（AD-18）。
 */
public interface UserHideRelationReader {

    /**
     * holder 是否隐藏了 target —— <b>不区分来源</b>（主动拉黑或举报隐藏都算）。
     *
     * <p>用于六处生效点里的<b>五处</b>：Feed 候选池 / 运营干预位 / 评论 R1·R2 /
     * 搜索话题聚合列表 / 互动通知抑制。
     *
     * @param holderId 不想看见对方的人（内部 {@code users.id}；仅服务端受控路径使用，不外露）
     * @param targetId 被隐藏的人（内部 {@code users.id}）
     */
    boolean isHidden(long holderId, long targetId);

    /**
     * holder 是否<b>主动拉黑</b>了 target —— <b>只认 {@code BLOCK}</b>，举报隐藏返回 {@code false}。
     *
     * <p>⚠️ <b>专供主页访问校验</b>（AD-11）。这是全版本<b>唯一</b>需要区分来源的地方。
     * 把主页拦截写成 {@link #isHidden} 会一并把举报隐藏拦掉——「已举报」状态无处显示、
     * 重复举报无入口，<b>FR-58 闭环当场作废</b>。
     */
    boolean isBlocked(long holderId, long targetId);
}
