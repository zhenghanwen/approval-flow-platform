-- ============================================================
-- M1 sys_* 扩展字段增量脚本（PRD 第四章映射，2026-10-28）
-- 适用场景：对【已按原版若依建好库】的既有环境执行一次。
-- 全新环境直接跑 sql/ry_20260417.sql 基线即可（已含本脚本全部字段）。
-- 执行幂等性：NOT EXISTS 守卫包裹，可安全重放。
-- ============================================================

-- 1) sys_user：直属主管 + 外部身份映射（PRD 4.5）
set @sql := if (
  (select count(*) from information_schema.columns
    where table_schema = database() and table_name = 'sys_user' and column_name = 'manager_user_id') = 0,
  'alter table sys_user add column manager_user_id bigint(20) default null comment ''直属主管用户ID（PRD 4.5 manager_user_id 映射，E1 主管规则）''',
  'select ''manager_user_id 已存在，跳过'''
);
prepare stmt from @sql; execute stmt; deallocate prepare stmt;

set @sql := if (
  (select count(*) from information_schema.columns
    where table_schema = database() and table_name = 'sys_user' and column_name = 'source_type') = 0,
  'alter table sys_user add column source_type varchar(20) default ''INTERNAL'' comment ''用户来源（INTERNAL/EXTERNAL，PRD 4.5）''',
  'select ''source_type 已存在，跳过'''
);
prepare stmt from @sql; execute stmt; deallocate prepare stmt;

set @sql := if (
  (select count(*) from information_schema.columns
    where table_schema = database() and table_name = 'sys_user' and column_name = 'external_provider_id') = 0,
  'alter table sys_user add column external_provider_id varchar(36) default null comment ''外部身份提供方ID（PRD 4.5）''',
  'select ''external_provider_id 已存在，跳过'''
);
prepare stmt from @sql; execute stmt; deallocate prepare stmt;

set @sql := if (
  (select count(*) from information_schema.columns
    where table_schema = database() and table_name = 'sys_user' and column_name = 'external_user_id') = 0,
  'alter table sys_user add column external_user_id varchar(200) default null comment ''外部用户ID（PRD 4.5）''',
  'select ''external_user_id 已存在，跳过'''
);
prepare stmt from @sql; execute stmt; deallocate prepare stmt;

-- 外部身份唯一约束（PRD：external_provider_id+external_user_id 唯一）
set @sql := if (
  (select count(*) from information_schema.statistics
    where table_schema = database() and table_name = 'sys_user' and index_name = 'uk_sys_user_external') = 0,
  'alter table sys_user add unique key uk_sys_user_external (external_provider_id, external_user_id)',
  'select ''uk_sys_user_external 已存在，跳过'''
);
prepare stmt from @sql; execute stmt; deallocate prepare stmt;

-- 2) sys_dept：部门负责人用户ID（PRD 4.2，E1 部门负责人规则）
set @sql := if (
  (select count(*) from information_schema.columns
    where table_schema = database() and table_name = 'sys_dept' and column_name = 'manager_user_id') = 0,
  'alter table sys_dept add column manager_user_id bigint(20) default null comment ''部门负责人用户ID（PRD 4.2 manager_user_id 映射，E1 部门负责人规则）''',
  'select ''sys_dept.manager_user_id 已存在，跳过'''
);
prepare stmt from @sql; execute stmt; deallocate prepare stmt;
