/**
 * 头像审核状态机与异步编排（内容审核 story 5，§5）。跨 bean 三段编排（openReview → scoreAndRoute → applyScoreOutcome），
 * 绝不在 service 内自调用 REQUIRES_NEW（cm-4 已踩坑并修复）。
 */
package com.tailtopia.avatarmoderation.service;
