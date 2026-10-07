/**
 * Tailsonality 宠物性格测试（V1.3.2 batch-a Epic 2 · FR-117 · 架构 delta §1）。
 *
 * <p>题库与计分是编译期常量（{@link com.tailtopia.tailsonality.domain.TailsonalityCatalog}），不建表、不开运营编辑；
 * 只落结果行 {@code tailsonality_results}。文案全在 App（AD-2），后端只存代号 + 能量 + 内容版本。
 *
 * <p>模块边界：宠物信息经 {@code profile.service.PetProfileQueryService} 读口取，不直接注入 profile 的仓库；
 * 删档由 {@code ProfileDeletionService} 反向调用 {@code TailsonalityDeletionService}（删档编排的既有做法）。
 */
package com.tailtopia.tailsonality;
