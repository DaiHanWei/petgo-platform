package com.tailtopia.app

import com.tencent.chat.flutter.push.tencent_cloud_chat_push.application.TencentCloudChatPushApplication

/**
 * 应用 Application：继承 TIMPush 插件基类（系统推送硬要求——它在进程启动期挂载推送监听
 * 与通知点击事件缓存，杀进程态点通知冷启动的 ext 透传依赖它）。
 * 后续如需自有 Application 初始化逻辑，直接加在本类，勿改回默认 ${applicationName}。
 */
class TailTopiaApplication : TencentCloudChatPushApplication()
