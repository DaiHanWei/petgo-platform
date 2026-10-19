package com.tailtopia.notify.port;

import java.util.Collection;
import java.util.Map;

/**
 * 通知列表取宠物名的读端口（V1.4.0 gift Story 1.2 · AC5.4）。<b>提供方无关</b>：notify 模块只认本接口，
 * 不 import 任何提供方（送礼 / 宠物档案）的类；实现由提供方模块给出（当前为送礼模块的单宠过渡实现）。
 *
 * <p>一页通知<b>一次</b>批量调用（AD-11 无 N+1）。缺 bean（切片测试）时通知中心按「无宠物名」文案渲染。
 */
public interface NotificationPetNames {

    /**
     * 批量取宠物名。查不到（宠物已删除）的 id 不出现在结果里。空入参返回空 Map。
     *
     * @param petIds 宠物 id（{@code pet_profiles.id}）
     */
    Map<Long, String> namesOf(Collection<Long> petIds);
}
