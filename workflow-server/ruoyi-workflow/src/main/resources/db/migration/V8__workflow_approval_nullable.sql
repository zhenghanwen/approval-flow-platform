-- ============================================================
-- V8：流程管理解耦审批类型（PRD 5.5 修订）
--   流程管理侧新建流程不再要求挂接审批类型（去掉 flow → approval_type 强制绑定），
--   流程独立存在；审批类型页仍可按需绑定流程版本（approval_type → flow 方向保留）。
--   wf_workflow_definition.approval_definition_id 改为可空。
-- ============================================================

alter table wf_workflow_definition
    modify column approval_definition_id varchar(36) null comment '所属审批类型（可空：流程独立于审批类型）';
