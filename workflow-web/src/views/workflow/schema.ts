import { getFormSchema, listForm, listFormVersion } from '@/api/workflow/form'
import type { WfSchemaField } from './fields'

/** 从 schema 响应中取出字段数组（兼容 { fields: [] } 与直接数组） */
export function extractSchemaFields(schema: any): WfSchemaField[] {
  if (!schema) return []
  const raw = Array.isArray(schema) ? schema : schema.fields
  if (!Array.isArray(raw)) return []
  return raw
    .filter((item: any) => item && (item.field || item.key))
    .map((item: any) => ({
      field: String(item.field || item.key),
      label: item.label,
      type: item.type,
      required: item.required,
      placeholder: item.placeholder,
      options: item.options
    }))
}

/**
 * 按表单版本 ID 反查表单定义，读取已发布 schema。
 * 后端 schema 存放在 MongoDB，读取失败（未启动/无权限/无快照）时返回空数组，由调用方回落到本地模板。
 */
export async function loadSchemaByVersionId(versionId?: string): Promise<WfSchemaField[]> {
  if (!versionId) return []
  let formId = ''
  try {
    const formRes = await listForm()
    const forms = formRes.data || []
    for (const form of forms) {
      try {
        const versionRes = await listFormVersion(form.id)
        const matched = (versionRes.data || []).find(item => item.id === versionId)
        if (matched) {
          formId = form.id
          break
        }
      } catch {
        /* 单个表单版本读取失败继续尝试 */
      }
    }
  } catch {
    return []
  }
  if (!formId) return []
  try {
    const schemaRes = await getFormSchema(formId)
    return extractSchemaFields(schemaRes.data)
  } catch {
    return []
  }
}
