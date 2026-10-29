import type {
  ApprovalStep,
  WfApprovalAction,
  WfApprovalDefinition,
  WfApprovalInstance,
  WfApprovalTask
} from '@/types'
import { listApprovalDefinition } from '@/api/workflow/definition'
import { getApproval, getApprovalTimeline } from '@/api/workflow/approval'
import { listWorkflow, listWorkflowVersion } from '@/api/workflow/workflow'
import request from '@/utils/request'

/** 审批单业务状态文案 */
const STATUS_TEXT: Record<string, string> = {
  DRAFT: '草稿',
  SUBMITTING: '提交中',
  PENDING: '审批中',
  APPROVED: '已通过',
  RETURNED: '已退回',
  WITHDRAWN: '已撤回',
  CANCELLED: '已取消',
  FAILED: '提交失败'
}

/** 审批单业务状态对应的标签类型 */
const STATUS_TAG: Record<string, string> = {
  DRAFT: 'info',
  SUBMITTING: 'warning',
  PENDING: 'primary',
  APPROVED: 'success',
  RETURNED: 'danger',
  WITHDRAWN: 'info',
  CANCELLED: 'info'
}

/**
 * 流程节点文案。
 * 后端只在 configJson.nodes 里存审批人配置，不返回 BPMN 节点名称，
 * 由「流程设计器」保存的流程会带 layout.name；种子数据没有名称，这里按已知节点 key 兜底。
 */
const NODE_TEXT: Record<string, string> = {
  apply: '提交申请',
  submit: '提交申请',
  deptApproval: '部门经理审批',
  managerApproval: '部门经理审批',
  financeApproval: '财务审批',
  gmApproval: '总经理审批',
  bossApproval: '总经理审批',
  hrApproval: '人事审批'
}

/** 审批动作文案 */
const ACTION_TEXT: Record<string, string> = {
  SUBMIT: '提交申请',
  APPROVE: '同意',
  REJECT: '驳回',
  TRANSFER: '转交',
  ADD_SIGN: '加签',
  RETURN: '退回',
  WITHDRAW: '撤回',
  CANCEL: '取消'
}

export function statusText(status?: string): string {
  if (!status) return '—'
  return STATUS_TEXT[status] || status
}

export function statusTagType(status?: string): string {
  if (!status) return 'info'
  return STATUS_TAG[status] || 'info'
}

export function actionText(actionType?: string): string {
  if (!actionType) return '—'
  return ACTION_TEXT[actionType] || actionType
}

/** 时间格式化为 yyyy-MM-dd HH:mm */
export function formatTime(value?: string): string {
  if (!value) return '—'
  return String(value).replace('T', ' ').slice(0, 16)
}

/** 审批类型缓存（按 id） */
let definitionCache: Promise<Map<string, WfApprovalDefinition>> | null = null

export function loadDefinitionMap(force = false): Promise<Map<string, WfApprovalDefinition>> {
  if (!definitionCache || force) {
    definitionCache = listApprovalDefinition()
      .then(res => {
        const map = new Map<string, WfApprovalDefinition>()
        ;(res.data || []).forEach(item => map.set(item.id, item))
        return map
      })
      .catch(() => {
        definitionCache = null
        return new Map<string, WfApprovalDefinition>()
      })
  }
  return definitionCache
}

/** 用户信息（昵称 / 部门），用于把审批人 id 显示成人名 */
export interface WfUserInfo {
  nickName?: string
  deptName?: string
}

let userCache: Promise<Map<string, WfUserInfo>> | null = null

/** 用户缓存（按 userId）。无 user:list 权限时静默失败，调用方回落到原始 id */
export function loadUserMap(force = false): Promise<Map<string, WfUserInfo>> {
  if (!userCache || force) {
    userCache = request({
      url: '/system/user/list',
      method: 'get',
      params: { pageNum: 1, pageSize: 1000 },
      silent: true
    } as any)
      .then((res: any) => {
        const map = new Map<string, WfUserInfo>()
        ;(res?.rows || []).forEach((user: any) => {
          map.set(String(user.userId), {
            nickName: user.nickName || user.userName,
            deptName: user.dept?.deptName
          })
        })
        return map
      })
      .catch(() => {
        userCache = null
        return new Map<string, WfUserInfo>()
      })
  }
  return userCache
}

/** 待办/已办列表行 */
export interface TaskRow extends WfApprovalTask {
  title?: string
  typeName?: string
  applicant?: string
  result?: string
  instance?: WfApprovalInstance
}

/**
 * 待办/已办列表补齐审批单信息。
 * 现有接口按任务投影返回，标题/申请人需回查审批单。
 */
export async function enrichTasks(tasks: WfApprovalTask[], withResult = false): Promise<TaskRow[]> {
  const definitions = await loadDefinitionMap()
  return Promise.all(
    tasks.map(async task => {
      const row: TaskRow = { ...task }
      try {
        const detail = await getApproval(task.approvalInstanceId)
        const instance = detail.data as WfApprovalInstance
        row.instance = instance
        row.title = instance?.title
        row.applicant = instance?.applicantNameSnapshot
        row.typeName = instance?.approvalDefinitionId
          ? definitions.get(instance.approvalDefinitionId)?.approvalName || '—'
          : '—'
      } catch {
        row.title = '—'
        row.applicant = '—'
        row.typeName = '—'
      }
      if (withResult) {
        row.result = await resolveResult(task)
      }
      return row
    })
  )
}

/** 已办结果：优先取时间线里同任务的动作 */
async function resolveResult(task: WfApprovalTask): Promise<string> {
  try {
    const res = await getApprovalTimeline(task.approvalInstanceId)
    const actions = res.data || []
    const matched = actions.filter(
      a => a.flowableTaskId && task.flowableTaskId && a.flowableTaskId === task.flowableTaskId
    )
    const hit = matched.length
      ? matched[matched.length - 1]
      : actions.filter(a => a.actionType === 'APPROVE' || a.actionType === 'REJECT').slice(-1)[0]
    if (hit?.actionType) return actionText(hit.actionType)
  } catch {
    /* 忽略时间线读取失败，按任务状态兜底 */
  }
  return task.taskStatus === 'CANCELLED' ? '已取消' : '已处理'
}

/** 由审批单 + 时间线生成流程步骤 */
export function buildSteps(
  instance: WfApprovalInstance | undefined,
  actions: WfApprovalAction[],
  nodeNames: Map<string, string> = new Map(),
  users: Map<string, WfUserInfo> = new Map()
): ApprovalStep[] {
  const steps: ApprovalStep[] = []
  const seen = new Set<string>()

  steps.push({
    key: 'submit',
    name: '提交申请',
    approver: instance?.applicantNameSnapshot ? `审批人：${instance.applicantNameSnapshot}` : undefined,
    time: instance?.submittedAt ? `时间：${formatTime(instance.submittedAt)}` : undefined,
    state: 'done'
  })

  actions
    .filter(a => a.actionType !== 'SUBMIT')
    .forEach(action => {
      const key = action.flowableTaskId || action.id
      if (seen.has(key)) return
      seen.add(key)
      const name =
        nodeNames.get(action.taskDefinitionKey || '') ||
        NODE_TEXT[action.taskDefinitionKey || ''] ||
        action.taskDefinitionKey ||
        actionText(action.actionType)
      const isReturnOrReject = action.actionType === 'REJECT' || action.actionType === 'RETURN'
      const operator = action.operatorUserId ? users.get(String(action.operatorUserId)) : undefined
      steps.push({
        key,
        name,
        approver: action.operatorUserId
          ? `审批人：${operator?.nickName || action.operatorUserId}`
          : undefined,
        time: action.createdAt ? `时间：${formatTime(action.createdAt)}` : undefined,
        comment: action.comment ? `意见：${action.comment}` : undefined,
        state: isReturnOrReject ? 'done' : 'done'
      })
    })

  // 当前节点（审批中）
  if (instance?.currentNodeName && instance.businessStatus === 'PENDING') {
    const exists = steps.some(s => s.name === instance.currentNodeName)
    if (!exists) {
      steps.push({
        key: instance.currentNodeKey || 'current',
        name: instance.currentNodeName,
        approver: '等待审批...',
        state: 'active'
      })
    } else {
      const current = steps.filter(s => s.name === instance.currentNodeName).pop()
      if (current) current.state = 'active'
    }
  }

  // 未到达节点（配置里存在但时间线没有的节点）
  nodeNames.forEach((name, key) => {
    if (steps.some(s => s.name === name)) return
    steps.push({ key, name, approver: '等待审批...', state: 'wait' })
  })

  return steps
}

/** 解析流程 configJson 中的节点名称 */
export function parseNodeNames(configJson?: string): Map<string, string> {
  const map = new Map<string, string>()
  if (!configJson) return map
  try {
    const root = JSON.parse(configJson)
    // layout 保存画布信息（name/type/x/y），nodes 保存审批人配置
    const layout = root?.layout || {}
    Object.keys(layout).forEach(key => {
      const name = layout[key]?.name || layout[key]?.nodeName
      if (name) map.set(key, name)
    })
    const nodes = root?.nodes || {}
    Object.keys(nodes).forEach(key => {
      if (map.has(key)) return
      const name = nodes[key]?.name || nodes[key]?.nodeName
      if (name) map.set(key, name)
    })
  } catch {
    /* 配置解析失败时忽略 */
  }
  return map
}

/** 读取审批类型关联流程（已发布版本）的节点名称 */
export async function loadFlowNodeNames(approvalDefinitionId?: string): Promise<Map<string, string>> {
  if (!approvalDefinitionId) return new Map()
  try {
    const res = await listWorkflow({ approvalDefinitionId })
    const workflows = (res.data || []).filter(item => item.approvalDefinitionId === approvalDefinitionId)
    const definition = workflows.find(item => item.status === 'PUBLISHED') || workflows[0]
    if (!definition) return new Map()
    const versionRes = await listWorkflowVersion(definition.id)
    const versions = (versionRes.data || [])
      .filter(item => item.status === 'PUBLISHED')
      .sort((a, b) => (b.versionNo || 0) - (a.versionNo || 0))
    return parseNodeNames(versions[0]?.configJson)
  } catch {
    return new Map()
  }
}
