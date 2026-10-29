import request from '@/utils/request'
import type { AjaxResult, WfApprovalAction, WfApprovalInstance } from '@/types'
import { genKey } from './task'

/** 提交审批 */
export function submitApproval(payload: {
  approvalDefinitionId: string
  formVersionId: string
  title: string
  formData: Record<string, any>
  ccUserIds?: string[]
  idempotencyKey?: string
}) {
  return request({
    url: '/workflow/approvals',
    method: 'post',
    data: { ...payload, idempotencyKey: payload.idempotencyKey || genKey() }
  })
}

/** 审批单详情 */
export function getApproval(id: string): Promise<AjaxResult<WfApprovalInstance>> {
  return request({
    url: `/workflow/approvals/${id}`,
    method: 'get'
  })
}

/** 审批时间线 */
export function getApprovalTimeline(id: string): Promise<AjaxResult<WfApprovalAction[]>> {
  return request({
    url: `/workflow/approvals/${id}/timeline`,
    method: 'get'
  })
}

/** 我发起的审批单 */
export function listMyApproval(): Promise<AjaxResult<WfApprovalInstance[]>> {
  return request({
    url: '/workflow/approvals/my',
    method: 'get'
  })
}

/** 撤回 */
export function withdrawApproval(id: string, reason?: string) {
  return request({
    url: `/workflow/approvals/${id}/withdraw`,
    method: 'post',
    data: { reason, idempotencyKey: genKey() }
  })
}

/** 取消 */
export function cancelApproval(id: string, reason?: string) {
  return request({
    url: `/workflow/approvals/${id}/cancel`,
    method: 'post',
    data: { reason, idempotencyKey: genKey() }
  })
}
