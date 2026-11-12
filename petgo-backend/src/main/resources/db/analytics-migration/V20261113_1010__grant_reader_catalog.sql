-- analytics-pipeline Story 1.2：给只读角色（AI 查数）授权事件清单与口径两张表（只在埋点库执行）。
-- 角色名由 Flyway 占位符 readerRole 传入（生产 analytics_reader / stag analytics_reader_stag）；
-- 本地 / CI 没有这个角色 → RAISE NOTICE 跳过（Story 1.1 定的条件授权写法）。
-- 只授 SELECT：两张表是 yaml 的投影，由后端启动时同步，只读账号不得写。
-- 🔴 本文件一旦被任何环境应用即冻结。

DO $$
BEGIN
  IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = '${readerRole}') THEN
    EXECUTE format('GRANT SELECT ON %I TO %I', 'event_catalog', '${readerRole}');
    EXECUTE format('GRANT SELECT ON %I TO %I', 'metric_definitions', '${readerRole}');
  ELSE
    RAISE NOTICE 'role ${readerRole} 不存在，跳过授权（本地 / CI 预期如此）';
  END IF;
END $$;
