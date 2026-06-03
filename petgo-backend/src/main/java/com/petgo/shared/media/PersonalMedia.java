package com.petgo.shared.media;

import java.util.ArrayList;
import java.util.List;

/**
 * 注销待删个人媒体聚合（Story 7.3）。各模块删除/匿名化时收集自身个人图，由注销作业统一删 OSS。
 * 放 shared 避免业务模块反向依赖 account 编排层。
 *
 * <ul>
 *   <li>{@code privateKeys}：私密桶②对象 key（分诊/健康/consult 存档图）。</li>
 *   <li>{@code publicUrls}：公开桶①个人图 URL（头像/宠物名片图，删除前由 media 解析回 key）。</li>
 * </ul>
 */
public record PersonalMedia(List<String> privateKeys, List<String> publicUrls) {

    public static PersonalMedia empty() {
        return new PersonalMedia(new ArrayList<>(), new ArrayList<>());
    }

    public static PersonalMedia ofPrivate(List<String> privateKeys) {
        return new PersonalMedia(privateKeys == null ? new ArrayList<>() : new ArrayList<>(privateKeys),
                new ArrayList<>());
    }

    public PersonalMedia merge(PersonalMedia other) {
        List<String> pk = new ArrayList<>(privateKeys);
        pk.addAll(other.privateKeys);
        List<String> pu = new ArrayList<>(publicUrls);
        pu.addAll(other.publicUrls);
        return new PersonalMedia(pk, pu);
    }
}
