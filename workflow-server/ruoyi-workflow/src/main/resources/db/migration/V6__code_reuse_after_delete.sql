-- ============================================================
-- V6：软删除后编码可复用（P8 收尾）
--   wf_approval_definition 唯一键从 (approval_code)
--   调整为 (approval_code, del_flag)：
--   已删除(del_flag='1')的记录不再占用编码，历史行不受影响。
-- ============================================================

alter table wf_approval_definition
    drop index uk_wf_approval_definition_code;

alter table wf_approval_definition
    add unique key uk_wf_approval_definition_code (approval_code, del_flag);
