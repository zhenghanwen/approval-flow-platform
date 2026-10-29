/**
 * 审批工作流 API 类型
 */

/** 审批任务（wf_approval_task 投影） */
export interface WfApprovalTask {
  id: string
  approvalInstanceId: string
  processInstanceId?: string
  flowableTaskId?: string
  taskDefinitionKey?: string
  taskName?: string
  assigneeUserId?: string
  originalAssigneeUserId?: string
  ownerUserId?: string
  taskStatus?: string
  delegatedToUserId?: string
  parentTaskId?: string
  dueAt?: string
  createdAt?: string
  completedAt?: string
}

/** 审批单 */
export interface WfApprovalInstance {
  id: string
  approvalNo?: string
  approvalDefinitionId?: string
  formVersionId?: string
  workflowVersionId?: string
  processInstanceId?: string
  applicantUserId?: string
  applicantNameSnapshot?: string
  applicantDeptId?: string
  applicantDeptSnapshot?: string
  title?: string
  formDataId?: string
  businessStatus?: string
  currentNodeKey?: string
  currentNodeName?: string
  submittedAt?: string
  completedAt?: string
  createdAt?: string
  updatedAt?: string
}

/** 审批操作记录 */
export interface WfApprovalAction {
  id: string
  approvalInstanceId?: string
  flowableTaskId?: string
  taskDefinitionKey?: string
  operatorUserId?: string
  actionType?: string
  comment?: string
  targetUserId?: string
  targetTaskKey?: string
  beforeStatus?: string
  afterStatus?: string
  createdAt?: string
}

/** 审批类型（wf_approval_definition） */
export interface WfApprovalDefinition {
  id: string
  approvalCode?: string
  approvalName?: string
  categoryId?: string
  description?: string
  icon?: string
  currentFormVersionId?: string
  currentFlowVersionId?: string
  allowWithdraw?: boolean
  allowCancel?: boolean
  status?: string
  createdBy?: string
  createdAt?: string
  updatedAt?: string
}

/** 流程定义 */
export interface WfWorkflowDefinition {
  id: string
  approvalDefinitionId?: string
  workflowCode?: string
  workflowName?: string
  status?: string
  currentVersion?: number
  createdBy?: string
  createdAt?: string
  updatedAt?: string
}

/** 流程版本 */
export interface WfWorkflowVersion {
  id: string
  workflowId?: string
  versionNo?: number
  flowableDeploymentId?: string
  processDefinitionId?: string
  processDefinitionKey?: string
  bpmnHash?: string
  configJson?: string
  status?: string
  publishedAt?: string
  publishedBy?: string
  createdAt?: string
}

/** 表单定义 */
export interface WfFormDefinition {
  id: string
  formCode?: string
  formName?: string
  description?: string
  currentVersionId?: string
  /** 当前发布版本号 */
  currentVersion?: number
  status?: string
  createdBy?: string
  createdAt?: string
  updatedAt?: string
}

/** 表单版本 */
export interface WfFormVersion {
  id: string
  formId?: string
  versionNo?: number
  mongoSchemaId?: string
  schemaHash?: string
  status?: string
  publishedAt?: string
  publishedBy?: string
  createdAt?: string
}

/** 表单数据 */
export interface WfFormDataDoc {
  approvalInstanceId: string
  formVersionId: string
  schemaHash?: string
  dataVersion?: number
  data: Record<string, any>
}

/** 审批时间线展示项 */
export interface ApprovalStep {
  key: string
  name: string
  approver?: string
  time?: string
  comment?: string
  state: 'done' | 'active' | 'wait'
}

/** 抄送记录（P7「抄送我的」，wf_approval_cc 关联审批单/审批类型） */
export interface WfApprovalCcVo {
  id: string
  approvalInstanceId?: string
  title?: string
  typeName?: string
  applicant?: string
  currentNodeName?: string
  ccTime?: string
  businessStatus?: string
  readFlag?: boolean
}
