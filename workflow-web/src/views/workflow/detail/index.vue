<template>
  <div class="app-container wf-detail-page" v-loading="loading">
    <div class="wf-detail">
      <!-- 左侧：审批信息 -->
      <section class="wf-detail__main">
        <header class="wf-detail__head">
          <h2 class="wf-detail__title">{{ instance?.title || '审批详情' }}</h2>
          <el-tag v-if="instance" :type="(statusTagType(instance.businessStatus) as any)" effect="light">
            {{ statusText(instance.businessStatus) }}
          </el-tag>
        </header>

        <div class="wf-detail__rows">
          <div class="wf-detail__row">
            <span class="wf-detail__label">审批标题</span>
            <span class="wf-detail__value">{{ instance?.title || '—' }}</span>
          </div>
          <div class="wf-detail__row">
            <span class="wf-detail__label">申请人</span>
            <span class="wf-detail__value">{{ applicantText }}</span>
          </div>
          <div v-for="row in formRows" :key="row.label" class="wf-detail__row">
            <span class="wf-detail__label">{{ row.label }}</span>
            <span class="wf-detail__value">{{ row.value }}</span>
          </div>
          <div class="wf-detail__row">
            <span class="wf-detail__label">申请时间</span>
            <span class="wf-detail__value">{{ formatTime(instance?.submittedAt) }}</span>
          </div>
          <div v-if="attachments.length" class="wf-detail__row">
            <span class="wf-detail__label">附件</span>
            <span class="wf-detail__value wf-detail__attachments">
              <span v-for="name in attachments" :key="name" class="wf-attachment">
                <el-icon><Paperclip /></el-icon>
                {{ name }}
              </span>
            </span>
          </div>
        </div>

        <el-empty
          v-if="!instance && !loading"
          description="审批单不存在或无权查看"
          :image-size="80"
        />
      </section>

      <!-- 右侧：审批流程 -->
      <aside class="wf-detail__side">
        <h3 class="wf-detail__side-title">审批流程</h3>
        <ApprovalSteps :steps="steps" />
      </aside>
    </div>

    <!-- 底部：审批操作 -->
    <div v-if="myTask" class="wf-action-bar">
      <el-input
        v-model="comment"
        class="wf-action-bar__comment"
        placeholder="填写审批意见（可选）..."
        maxlength="200"
        clearable
      />
      <el-button class="wf-btn wf-btn--approve" :loading="acting" @click="handleApprove">
        <el-icon><Check /></el-icon>
        同意
      </el-button>
      <el-button class="wf-btn wf-btn--reject" :loading="acting" @click="handleReject">
        <el-icon><Close /></el-icon>
        驳回
      </el-button>
      <el-button class="wf-btn wf-btn--transfer" @click="openTransfer">转办</el-button>
    </div>

    <action-dialog ref="actionRef" :task-id="myTask?.id || ''" type="transfer" @success="loadDetail" />
  </div>
</template>

<script setup lang="ts" name="WorkflowDetail">
import { getApproval, getApprovalTimeline } from '@/api/workflow/approval'
import { getApprovalDefinition } from '@/api/workflow/definition'
import { getFormData } from '@/api/workflow/form'
import { approveTask, listTodoTask, rejectTask } from '@/api/workflow/task'
import type { ApprovalStep, WfApprovalAction, WfApprovalInstance, WfApprovalTask } from '@/types'
import ActionDialog from '../components/ActionDialog.vue'
import ApprovalSteps from '../components/ApprovalSteps.vue'
import { WF_FIELD_LABELS, getFormMeta, type WfFormMeta, type WfSchemaField } from '../fields'
import { loadSchemaByVersionId } from '../schema'
import {
  buildSteps,
  formatTime,
  loadFlowNodeNames,
  loadUserMap,
  statusTagType,
  statusText,
  type WfUserInfo
} from '../utils'

const route = useRoute()
const { proxy } = getCurrentInstance() as any

const approvalId = String(route.params.id || '')
const loading = ref(true)
const acting = ref(false)
const comment = ref('')
const instance = ref<WfApprovalInstance | null>(null)
const actions = ref<WfApprovalAction[]>([])
const formData = ref<Record<string, any>>({})
const formMeta = ref<WfFormMeta | null>(null)
const schemaFields = ref<WfSchemaField[]>([])
const steps = ref<ApprovalStep[]>([])
const myTask = ref<WfApprovalTask | null>(null)
const actionRef = ref()
const users = ref<Map<string, WfUserInfo>>(new Map())

const applicantText = computed(() => {
  const name = instance.value?.applicantNameSnapshot || '—'
  const dept =
    instance.value?.applicantDeptSnapshot ||
    users.value.get(String(instance.value?.applicantUserId || ''))?.deptName
  return dept ? `${name} / ${dept}` : name
})

function formatFieldValue(label: string, value: any, money = false) {
  if (value === null || value === undefined || value === '') return '—'
  if (Array.isArray(value)) return value.map(String).join('、')
  if (typeof value === 'object') {
    const obj: any = value
    return obj.label || obj.name || obj.deptName || obj.fileName || JSON.stringify(obj)
  }
  if (money || label.includes('金额')) {
    const num = Number(value)
    if (!isNaN(num)) {
      return `￥${num.toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })}`
    }
  }
  return String(value)
}

/** 表单数据展示：优先按后端 schema 字段顺序，其次按本地模板，未知字段追加末尾 */
const formRows = computed(() => {
  const data = formData.value || {}
  const rows: Array<{ label: string; value: string }> = []
  const known = new Set<string>(['title', 'attachment', 'applicant', 'applicantId', 'dept', 'deptId', 'deptName'])

  schemaFields.value.forEach(field => {
    if (known.has(field.field)) return
    known.add(field.field)
    const value = data[field.field]
    if (value === undefined || value === null || value === '') return
    const label = field.label || WF_FIELD_LABELS[field.field] || field.field
    rows.push({ label, value: formatFieldValue(label, value, field.type === 'money') })
  })

  const templateFields = (formMeta.value?.fields || []).filter(
    field => field.key !== 'title' && field.type !== 'upload'
  )
  templateFields.forEach(field => {
    if (known.has(field.key)) return
    known.add(field.key)
    const value = data[field.key]
    if (value === undefined || value === null || value === '') return
    rows.push({
      label: field.label,
      value: formatFieldValue(field.label, value, field.type === 'number' && field.prefix === '￥')
    })
  })

  Object.keys(data).forEach(key => {
    if (known.has(key)) return
    const value = data[key]
    if (value === undefined || value === null || value === '') return
    const label = WF_FIELD_LABELS[key] || key
    rows.push({ label, value: formatFieldValue(label, value) })
  })
  return rows
})

const attachments = computed<string[]>(() => {
  const raw = formData.value?.attachment
  if (!raw) return []
  if (Array.isArray(raw)) return raw.map(String)
  return String(raw)
    .split(',')
    .map(item => item.trim())
    .filter(Boolean)
})

async function loadDetail() {
  loading.value = true
  try {
    const [detailRes, timelineRes] = await Promise.all([
      getApproval(approvalId),
      getApprovalTimeline(approvalId).catch(() => ({ data: [] }) as any)
    ])
    instance.value = detailRes.data || null
    actions.value = timelineRes.data || []

    // 审批类型 → 表单字段模板
    if (instance.value?.approvalDefinitionId) {
      try {
        const defRes = await getApprovalDefinition(instance.value.approvalDefinitionId)
        if (defRes.data?.approvalCode) {
          formMeta.value = getFormMeta(defRes.data.approvalCode)
        }
      } catch {
        formMeta.value = null
      }
    }

    // 表单数据（草稿/提交中可能不存在）
    if (instance.value && !['DRAFT', 'SUBMITTING'].includes(instance.value.businessStatus || '')) {
      try {
        const dataRes = await getFormData(approvalId)
        formData.value = dataRes.data?.data || {}
      } catch {
        formData.value = {}
      }
    }

    // 后端表单 schema（用于字段标签与顺序；读取失败回落到模板标签）
    schemaFields.value = await loadSchemaByVersionId(instance.value?.formVersionId)

    // 流程节点名称（用于时间线里未到达节点的展示）
    const [nodeNames, userMap] = await Promise.all([
      loadFlowNodeNames(instance.value?.approvalDefinitionId),
      loadUserMap()
    ])
    users.value = userMap
    steps.value = buildSteps(instance.value || undefined, actions.value, nodeNames, userMap)

    // 当前用户是否有待办任务
    try {
      const todoRes = await listTodoTask()
      myTask.value = (todoRes.data || []).find(task => task.approvalInstanceId === approvalId) || null
    } catch {
      myTask.value = null
    }
  } catch {
    instance.value = null
  } finally {
    loading.value = false
  }
}

async function handleApprove() {
  if (!myTask.value) return
  try {
    await proxy.$modal.confirm('确认同意该审批申请？')
  } catch {
    return
  }
  acting.value = true
  try {
    await approveTask(myTask.value.flowableTaskId || myTask.value.id, comment.value)
    proxy.$modal.msgSuccess('操作成功')
    comment.value = ''
    await loadDetail()
  } finally {
    acting.value = false
  }
}

async function handleReject() {
  if (!myTask.value) return
  if (!comment.value.trim()) {
    proxy.$modal.msgWarning('请填写驳回意见')
    return
  }
  try {
    await proxy.$modal.confirm('确认驳回该审批申请？')
  } catch {
    return
  }
  acting.value = true
  try {
    await rejectTask(myTask.value.flowableTaskId || myTask.value.id, comment.value)
    proxy.$modal.msgSuccess('已驳回')
    comment.value = ''
    await loadDetail()
  } finally {
    acting.value = false
  }
}

function openTransfer() {
  if (!myTask.value) {
    proxy.$modal.msgWarning('当前没有可转办的待办任务')
    return
  }
  actionRef.value?.open()
}

loadDetail()
</script>

<style scoped lang="scss">
.wf-detail-page {
  padding-bottom: 8px;
}

.wf-detail {
  display: flex;
  align-items: stretch;
  gap: 16px;
}

.wf-detail__main {
  flex: 1;
  min-width: 0;
  min-height: 560px;
  background: #fff;
  border-radius: 4px;
  padding: 24px 28px;
}

.wf-detail__head {
  display: flex;
  align-items: center;
  gap: 14px;
  margin-bottom: 26px;
}

.wf-detail__title {
  margin: 0;
  font-size: 18px;
  font-weight: 600;
  color: #303133;
}

.wf-detail__rows {
  display: flex;
  flex-direction: column;
  gap: 20px;
}

.wf-detail__row {
  display: flex;
  align-items: flex-start;
  font-size: 14px;
  line-height: 22px;
}

.wf-detail__label {
  flex: none;
  width: 110px;
  color: #909399;
}

.wf-detail__value {
  flex: 1;
  min-width: 0;
  color: #303133;
  word-break: break-all;
  white-space: pre-wrap;
}

.wf-detail__attachments {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.wf-attachment {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 3px 10px;
  border-radius: 3px;
  background: #e8f4ff;
  color: #409eff;
  font-size: 13px;
  line-height: 18px;
}

.wf-detail__side {
  flex: none;
  width: 368px;
  background: #fff;
  border-radius: 4px;
  padding: 20px 24px;
}

.wf-detail__side-title {
  margin: 0 0 22px;
  font-size: 16px;
  font-weight: 600;
  color: #303133;
}

.wf-action-bar {
  display: flex;
  align-items: center;
  gap: 14px;
  margin-top: 16px;
  padding: 16px 24px;
  background: #fff;
  border-radius: 4px;
}

.wf-action-bar__comment {
  flex: 1;
  min-width: 0;
}

.wf-btn {
  width: 88px;
  height: 34px;
  border: none;
  color: #fff;
  font-size: 14px;

  &--approve {
    background: #67c23a;
  }

  &--reject {
    background: #f56c6c;
  }

  &--transfer {
    background: #e6a23c;
  }

  &:hover,
  &:focus {
    opacity: 0.88;
    color: #fff;
  }
}

@media (max-width: 1100px) {
  .wf-detail {
    flex-direction: column;
  }

  .wf-detail__side {
    width: 100%;
  }
}
</style>
