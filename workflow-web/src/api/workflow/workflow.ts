import request from '@/utils/request'
import type { AjaxResult, WfWorkflowDefinition, WfWorkflowVersion } from '@/types'

/** 流程定义列表 */
export function listWorkflow(query?: Partial<WfWorkflowDefinition>): Promise<AjaxResult<WfWorkflowDefinition[]>> {
  return request({
    url: '/workflow/workflows/list',
    method: 'get',
    params: query
  })
}

/** 流程定义详情 */
export function getWorkflow(id: string): Promise<AjaxResult<WfWorkflowDefinition>> {
  return request({
    url: '/workflow/workflows/' + id,
    method: 'get'
  })
}

/** 流程版本列表 */
export function listWorkflowVersion(id: string): Promise<AjaxResult<WfWorkflowVersion[]>> {
  return request({
    url: '/workflow/workflows/' + id + '/versions',
    method: 'get'
  })
}

/** 校验流程 */
export function validateWorkflow(id: string, payload: { bpmnXml: string; configJson?: string; workflowCode?: string; workflowName?: string }) {
  return request({
    url: '/workflow/workflows/' + id + '/validate',
    method: 'post',
    data: payload
  })
}

/** 发布流程 */
export function publishWorkflow(id: string, payload: { bpmnXml: string; configJson?: string; workflowCode?: string; workflowName?: string }) {
  return request({
    url: '/workflow/workflows/' + id + '/publish',
    method: 'post',
    data: payload
  })
}

/** 新建流程定义（approvalDefinitionId 可选：流程管理侧新建流程不再绑定审批类型） */
export function addWorkflow(payload: {
  approvalDefinitionId?: string
  workflowCode: string
  workflowName: string
}) {
  return request({
    url: '/workflow/workflows',
    method: 'post',
    data: payload
  })
}
