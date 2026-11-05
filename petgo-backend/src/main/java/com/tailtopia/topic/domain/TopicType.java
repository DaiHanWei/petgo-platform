package com.tailtopia.topic.domain;

/** 话题类型（V1.4.2 social-topic · Story 4.1 · D-2）。落库 varchar UPPER_SNAKE（{@code ck_topics_type}）。 */
public enum TopicType {
    /** 官方话题：后台新建 / 由用户话题升级（6.1）。 */
    OFFICIAL,
    /** 用户话题：发帖正文里首次出现时自动建（{@code TopicService.attach}）。 */
    USER
}
