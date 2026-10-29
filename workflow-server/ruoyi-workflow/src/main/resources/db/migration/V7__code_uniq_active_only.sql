-- ============================================================
-- V7：编码唯一性改为「仅生效行唯一」（P8 收尾修正）
--   V6 的 (approval_code, del_flag) 复合唯一键虽支持重建，
--   但「重建后再次删除」会在 (code,'1') 上二次冲突。
--   改为函数索引：del_flag='0' 时唯一（生效行），
--   del_flag='1' 时表达式为 NULL（MySQL 唯一索引允许多个 NULL），
--   历史已删除行永不占用编码、也不与后续删除冲突。
-- ============================================================

alter table wf_approval_definition
    drop index uk_wf_approval_definition_code;

create unique index uk_wf_approval_definition_code
    on wf_approval_definition ((if(del_flag = '0', approval_code, null)));
