/**
 * 头像审核（内容审核 story 5）：用户/宠物头像「先放行、后异步审核」+ 违规重置平台默认头像。
 * 与名称侧 {@code namemoderation} 并列同构，独立 {@code avatar_reviews} 表，不复用帖子 manual_review_queue（§4.1）。
 */
package com.tailtopia.avatarmoderation.domain;
