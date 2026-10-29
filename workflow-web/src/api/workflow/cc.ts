import request from '@/utils/request'
import type { AjaxResult, WfApprovalCcVo } from '@/types'

/** 我的抄送列表（P7） */
export function listCcApproval(): Promise<AjaxResult<WfApprovalCcVo[]>> {
  return request({
    url: '/workflow/cc/list',
    method: 'get'
  })
}

/** 标记抄送已读 */
export function markCcRead(id: string) {
  return request({
    url: `/workflow/cc/${id}/read`,
    method: 'post'
  })
}
