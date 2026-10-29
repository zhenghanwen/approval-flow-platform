import request from '@/utils/request'
import type { AjaxResult, WfApprovalDefinition } from '@/types'

/** 审批类型列表 */
export function listApprovalDefinition(query?: Partial<WfApprovalDefinition>): Promise<AjaxResult<WfApprovalDefinition[]>> {
  return request({
    url: '/workflow/approval-definitions/list',
    method: 'get',
    params: query
  })
}

/** 审批类型详情 */
export function getApprovalDefinition(id: string): Promise<AjaxResult<WfApprovalDefinition>> {
  return request({
    url: `/workflow/approval-definitions/${id}`,
    method: 'get'
  })
}

/** 新增审批类型 */
export function addApprovalDefinition(data: Partial<WfApprovalDefinition>): Promise<AjaxResult<WfApprovalDefinition>> {
  return request({
    url: '/workflow/approval-definitions',
    method: 'post',
    data
  })
}

/** 修改审批类型 */
export function updateApprovalDefinition(id: string, data: Partial<WfApprovalDefinition>) {
  return request({
    url: `/workflow/approval-definitions/${id}`,
    method: 'put',
    data
  })
}

/** 发布审批类型 */
export function publishApprovalDefinition(id: string) {
  return request({
    url: `/workflow/approval-definitions/${id}/publish`,
    method: 'post'
  })
}

/** 停用审批类型 */
export function disableApprovalDefinition(id: string) {
  return request({
    url: `/workflow/approval-definitions/${id}/disable`,
    method: 'post'
  })
}

/** 删除审批类型（软删除，P8） */
export function deleteApprovalDefinition(id: string) {
  return request({
    url: `/workflow/approval-definitions/${id}`,
    method: 'delete'
  })
}
