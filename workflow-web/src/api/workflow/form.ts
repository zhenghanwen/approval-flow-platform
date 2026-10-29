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

/** 新建表单定义（P10 表单管理） */
export function createForm(payload: { formCode: string; formName: string }): Promise<AjaxResult<WfFormDefinition>> {
  return request({
    url: '/workflow/forms',
    method: 'post',
    data: payload
  })
}

/** 保存表单版本草稿（schema 落 MongoDB，不发布） */
export function saveFormSchemaDraft(formId: string, schema: any): Promise<AjaxResult<WfFormVersion>> {
  return request({
    url: `/workflow/forms/${formId}/versions/draft`,
    method: 'post',
    data: schema
  })
}

/** 发布表单版本（快照不可变） */
export function publishFormVersion(formId: string, versionId: string): Promise<AjaxResult<WfFormVersion>> {
  return request({
    url: `/workflow/forms/${formId}/versions/${versionId}/publish`,
    method: 'put'
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
