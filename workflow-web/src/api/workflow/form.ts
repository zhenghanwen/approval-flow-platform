import request from '@/utils/request'
import type { AjaxResult, WfFormDataDoc, WfFormDefinition, WfFormVersion } from '@/types'

/** 表单定义列表 */
export function listForm(query?: Record<string, any>): Promise<AjaxResult<WfFormDefinition[]>> {
  return request({
    url: '/workflow/forms/list',
    method: 'get',
    params: query
  })
}

/** 表单版本列表 */
export function listFormVersion(formId: string): Promise<AjaxResult<WfFormVersion[]>> {
  return request({
    url: `/workflow/forms/${formId}/versions`,
    method: 'get'
  })
}

/** 表单 schema（读取失败由页面回落到本地模板，故静默处理） */
export function getFormSchema(formId: string) {
  return request({
    url: '/workflow/forms/' + formId + '/schema',
    method: 'get',
    silent: true
  } as any)
}

/** 保存草稿 */
export function saveFormDraft(payload: { formVersionId: string; data: any; title: string }) {
  return request({
    url: '/workflow/form-data/draft',
    method: 'post',
    data: payload
  })
}

/** 提交表单 */
export function submitFormData(payload: { formVersionId: string; data: any; title: string }) {
  return request({
    url: '/workflow/form-data/submit',
    method: 'post',
    data: payload
  })
}

/** 读取审批单表单数据（草稿/提交中可能不存在，静默处理） */
export function getFormData(approvalInstanceId: string): Promise<AjaxResult<WfFormDataDoc>> {
  return request({
    url: `/workflow/form-data/${approvalInstanceId}`,
    method: 'get',
    silent: true
  } as any)
}
