/**
 * 审批表单字段配置。
 *
 * 后端表单 schema 存在 MongoDB（FormSchemaVersionDoc），本地未启动后端时无法读取，
 * 这里按审批类型给出与设计稿一致的字段模板；提交时字段 key 即 formData 的 key。
 */

export type WfFieldType = 'input' | 'textarea' | 'number' | 'date' | 'select' | 'upload' | 'switch'

export interface WfField {
  key: string
  label: string
  type: WfFieldType
  placeholder?: string
  required?: boolean
  /** 数字字段前缀，如 ￥ */
  prefix?: string
  /** 数字字段后缀，如 天 */
  suffix?: string
  options?: Array<{ label: string; value: string }>
  /** 多选（checkbox 映射为多选下拉） */
  multiple?: boolean
  /** 文本域行数 */
  rows?: number
}

export interface WfFormMeta {
  code: string
  name: string
  /** 页面标题，如「采购审批申请」 */
  pageTitle: string
  description: string
  icon: string
  fields: WfField[]
}

const titleField = (placeholder: string): WfField => ({
  key: 'title',
  label: '审批标题',
  type: 'input',
  placeholder,
  required: true
})

const reasonField = (label: string, placeholder: string): WfField => ({
  key: 'reason',
  label,
  type: 'textarea',
  placeholder,
  rows: 4,
  required: true
})

const attachmentField: WfField = {
  key: 'attachment',
  label: '附件上传',
  type: 'upload'
}

/** 设计稿里的 6 个审批类型 */
export const WF_FORM_METAS: WfFormMeta[] = [
  {
    code: 'LEAVE',
    name: '请假申请',
    pageTitle: '请假审批申请',
    description: '事假/病假/年假等请假流程',
    icon: '📝',
    fields: [
      titleField('请输入审批标题'),
      {
        key: 'leaveType',
        label: '请假类型',
        type: 'select',
        required: true,
        options: [
          { label: '事假', value: '事假' },
          { label: '病假', value: '病假' },
          { label: '年假', value: '年假' },
          { label: '调休', value: '调休' }
        ]
      },
      { key: 'startDate', label: '开始日期', type: 'date', required: true },
      { key: 'endDate', label: '结束日期', type: 'date', required: true },
      { key: 'days', label: '请假天数', type: 'number', required: true, suffix: '天' },
      reasonField('请假事由', '请填写请假事由'),
      attachmentField
    ]
  },
  {
    code: 'PURCHASE',
    name: '采购审批',
    pageTitle: '采购审批申请',
    description: '办公设备、物资采购申请',
    icon: '🛒',
    fields: [
      titleField('请输入审批标题'),
      { key: 'amount', label: '采购金额', type: 'number', required: true, prefix: '￥' },
      reasonField('采购事由', '请填写采购事由，例如：申请采购笔记本电脑 2 台'),
      { key: 'supplier', label: '供应商', type: 'input', placeholder: '请输入供应商名称' },
      attachmentField
    ]
  },
  {
    code: 'EXPENSE',
    name: '费用报销',
    pageTitle: '费用报销申请',
    description: '差旅、办公费用报销',
    icon: '💰',
    fields: [
      titleField('请输入审批标题'),
      {
        key: 'category',
        label: '费用类型',
        type: 'select',
        required: true,
        options: [
          { label: '差旅费', value: '差旅费' },
          { label: '办公费', value: '办公费' },
          { label: '招待费', value: '招待费' },
          { label: '其他', value: '其他' }
        ]
      },
      { key: 'amount', label: '报销金额', type: 'number', required: true, prefix: '￥' },
      { key: 'expenseDate', label: '费用日期', type: 'date', required: true },
      reasonField('费用说明', '请填写费用说明'),
      attachmentField
    ]
  },
  {
    code: 'TRAVEL',
    name: '出差申请',
    pageTitle: '出差审批申请',
    description: '外出出差申请与备案',
    icon: '✈️',
    fields: [
      titleField('请输入审批标题'),
      { key: 'destination', label: '出差地点', type: 'input', placeholder: '请输入出差地点', required: true },
      { key: 'startDate', label: '开始日期', type: 'date', required: true },
      { key: 'endDate', label: '结束日期', type: 'date', required: true },
      { key: 'days', label: '出差天数', type: 'number', required: true, suffix: '天' },
      reasonField('出差事由', '请填写出差事由'),
      attachmentField
    ]
  },
  {
    code: 'SEAL',
    name: '用印申请',
    pageTitle: '用印审批申请',
    description: '公章/合同用印审批',
    icon: '📄',
    fields: [
      titleField('请输入审批标题'),
      {
        key: 'sealType',
        label: '印章类型',
        type: 'select',
        required: true,
        options: [
          { label: '公章', value: '公章' },
          { label: '合同章', value: '合同章' },
          { label: '财务章', value: '财务章' },
          { label: '法人章', value: '法人章' }
        ]
      },
      { key: 'copies', label: '用印份数', type: 'number', required: true, suffix: '份' },
      reasonField('用印事由', '请填写用印事由'),
      attachmentField
    ]
  },
  {
    code: 'CONTRACT',
    name: '合同审批',
    pageTitle: '合同审批申请',
    description: '合同起草与审核流程',
    icon: '📦',
    fields: [
      titleField('请输入审批标题'),
      { key: 'contractName', label: '合同名称', type: 'input', placeholder: '请输入合同名称', required: true },
      { key: 'counterparty', label: '合同对方', type: 'input', placeholder: '请输入合同对方名称', required: true },
      { key: 'amount', label: '合同金额', type: 'number', required: true, prefix: '￥' },
      reasonField('合同说明', '请填写合同说明'),
      attachmentField
    ]
  }
]

/** 设计稿之外的审批类型按通用模板渲染 */
const GENERIC_META: WfFormMeta = {
  code: 'GENERIC',
  name: '审批申请',
  pageTitle: '审批申请',
  description: '通用审批流程',
  icon: '📋',
  fields: [
    titleField('请输入审批标题'),
    reasonField('申请事由', '请填写申请事由'),
    attachmentField
  ]
}

/** 审批编码 → 模板编码的模糊匹配（后端编码形如 expense_approval / m4_expense_approval） */
const FORM_CODE_ALIASES: Array<{ match: string; code: string }> = [
  { match: 'leave', code: 'LEAVE' },
  { match: 'purchase', code: 'PURCHASE' },
  { match: 'expense', code: 'EXPENSE' },
  { match: 'travel', code: 'TRAVEL' },
  { match: 'seal', code: 'SEAL' },
  { match: 'contract', code: 'CONTRACT' }
]

export function getFormMeta(code?: string): WfFormMeta {
  if (!code) return WF_FORM_METAS.find(item => item.code === 'PURCHASE') || GENERIC_META
  const upper = code.toUpperCase()
  const exact = WF_FORM_METAS.find(item => item.code === upper)
  if (exact) return exact
  const alias = FORM_CODE_ALIASES.find(item => upper.includes(item.match.toUpperCase()))
  if (alias) {
    const matched = WF_FORM_METAS.find(item => item.code === alias.code)
    if (matched) return { ...matched, code: upper, name: matched.name }
  }
  return {
    ...GENERIC_META,
    code: upper,
    name: code,
    pageTitle: `${code} 审批申请`
  }
}

/** 字段 key → 中文标签（详情页展示表单数据用） */
export const WF_FIELD_LABELS: Record<string, string> = (() => {
  const map: Record<string, string> = {
    applicant: '申请人',
    dept: '所属部门',
    submittedAt: '申请时间',
    attachment: '附件'
  }
  WF_FORM_METAS.forEach(meta => {
    meta.fields.forEach(field => {
      map[field.key] = field.label
    })
  })
  return map
})()

/** 详情页里不重复展示的字段（已作为头部/独立行展示） */
export const WF_DETAIL_SKIP_KEYS = ['title', 'attachment']

/** 后端表单 schema 字段（PRD 5.4：{ field, type, label, required, options }） */
export interface WfSchemaField {
  field: string
  label?: string
  type?: string
  required?: boolean
  placeholder?: string
  options?: any
}

function normalizeOptions(raw: any): Array<{ label: string; value: string }> {
  if (!Array.isArray(raw)) return []
  return raw
    .map(item => {
      if (item === null || item === undefined) return null
      if (typeof item === 'string' || typeof item === 'number') {
        return { label: String(item), value: String(item) }
      }
      const label = item.label ?? item.name ?? item.value ?? ''
      const value = item.value ?? item.label ?? item.name ?? ''
      if (value === '') return null
      return { label: String(label || value), value: String(value) }
    })
    .filter((item): item is { label: string; value: string } => !!item)
}

/** 后端 schema → 渲染用字段配置 */
export function schemaToWfFields(schemaFields: WfSchemaField[]): WfField[] {
  const fields: WfField[] = []
  schemaFields.forEach(item => {
    const key = item.field
    if (!key) return
    const type = String(item.type || 'text')
    const field: WfField = {
      key,
      label: item.label || key,
      type: 'input',
      required: !!item.required,
      placeholder: item.placeholder
    }
    switch (type) {
      case 'textarea':
        field.type = 'textarea'
        field.rows = 4
        break
      case 'number':
        field.type = 'number'
        break
      case 'money':
        field.type = 'number'
        field.prefix = '￥'
        break
      case 'date':
      case 'datetime':
        field.type = 'date'
        break
      case 'select':
      case 'radio':
        field.type = 'select'
        field.options = normalizeOptions(item.options)
        break
      case 'checkbox':
        field.type = 'select'
        field.multiple = true
        field.options = normalizeOptions(item.options)
        break
      case 'boolean':
        field.type = 'switch'
        break
      case 'file':
        field.type = 'upload'
        break
      default:
        field.type = 'input'
    }
    fields.push(field)
  })
  return fields
}
