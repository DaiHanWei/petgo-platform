package com.tailtopia.healthreminder.domain;

/** 提醒点开落点（PRD 附录 C Route）：表单预填今天 / 表单日期留空 / 记录页聚焦。安全情形在点开时另判（FR-167）。 */
public enum ReminderRoute {
    FORM,
    FORM_DATE_EMPTY,
    PAGE
}
