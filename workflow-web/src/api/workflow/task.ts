import request from '@/utils/request'
import type { AjaxResult, WfApprovalTask } from '@/types'

/** 我的待办 */
export function listTodoTask(): Promise<AjaxResult<WfApprovalTask[]>> {
  return request({
    url: '/workflow/tasks/todo',
    method: 'get'
  })
}

/** 我的已办 */
export function listDoneTask(): Promise<AjaxResult<WfApprovalTask[]>> {
  return request({
    url: '/workflow/tasks/done',
    method: 'get'
  })
}

/** 同意 */
export function approveTask(taskId: string, comment?: string, idempotencyKey?: string) {
  return request({
    url: `/workflow/tasks/${taskId}/approve`,
    method: 'post',
    data: { comment, idempotencyKey: idempotencyKey || genKey() }
  })
}

/** 驳回 */
export function rejectTask(taskId: string, comment?: string, idempotencyKey?: string) {
  return request({
    url: `/workflow/tasks/${taskId}/reject`,
    method: 'post',
    data: { comment, idempotencyKey: idempotencyKey || genKey() }
  })
}

/** 转交 */
export function transferTask(taskId: string, toUserId: string, reason?: string, idempotencyKey?: string) {
  return request({
    url: `/workflow/tasks/${taskId}/transfer`,
    method: 'post',
    data: { toUserId, reason, idempotencyKey: idempotencyKey || genKey() }
  })
}

/** 加签 */
export function addSignTask(
  taskId: string,
  payload: { signType: string; addUsers: string[]; reason?: string; idempotencyKey?: string }
) {
  return request({
    url: `/workflow/tasks/${taskId}/add-sign`,
    method: 'post',
    data: { ...payload, idempotencyKey: payload.idempotencyKey || genKey() }
  })
}

/** 退回 */
export function returnTask(taskId: string, targetNodeKey: string, reason?: string, idempotencyKey?: string) {
  return request({
    url: `/workflow/tasks/${taskId}/return`,
    method: 'post',
    data: { targetNodeKey, reason, idempotencyKey: idempotencyKey || genKey() }
  })
}

/** 可退回节点 */
export function listReturnTargets(taskId: string) {
  return request({
    url: `/workflow/tasks/${taskId}/return-targets`,
    method: 'get'
  })
}

/** 生成幂等键 */
export function genKey(): string {
  return `${Date.now()}-${Math.random().toString(36).slice(2, 10)}`
}
