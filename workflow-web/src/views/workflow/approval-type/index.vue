<template>
  <div class="app-container">
    <div class="wf-type__header">
      <h2 class="wf-type__title">审批类型配置</h2>
      <el-button type="primary" icon="Plus" @click="openCreate">新建审批类型</el-button>
    </div>

    <div class="wf-table-card">
      <el-table v-loading="loading" class="wf-table" :data="rows" row-key="id">
        <el-table-column label="编码" prop="approvalCode" min-width="200" show-overflow-tooltip />
        <el-table-column label="名称" prop="approvalName" min-width="150" show-overflow-tooltip />
        <el-table-column label="绑定表单" min-width="230">
          <template #default="{ row }">
            <span class="wf-binding">{{ formLabel(row.currentFormVersionId) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="绑定流程" min-width="230">
          <template #default="{ row }">
            <span class="wf-binding wf-binding--link" @click="openDesigner(row)">
              {{ flowLabel(row.currentFlowVersionId) }}
            </span>
          </template>
        </el-table-column>
        <el-table-column label="状态" min-width="100">
          <template #default="{ row }">
            <span :class="['wf-type-status', `wf-type-status--${row.status}`]">{{ statusLabel(row.status) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="200">
          <template #default="{ row }">
            <span class="wf-op" @click="openEdit(row)">编辑</span>
            <span class="wf-op-sep">|</span>
            <span class="wf-op" @click="handlePublish(row)">发布</span>
            <span class="wf-op-sep">|</span>
            <span class="wf-op" @click="handleDelete(row)">删除</span>
          </template>
        </el-table-column>
        <template #empty>
          <el-empty description="暂无审批类型" :image-size="90" />
        </template>
      </el-table>
    </div>

    <!-- 新建 / 编辑 -->
    <el-dialog
      v-model="dialog.visible"
      :title="dialog.isEdit ? '编辑审批类型' : '新建审批类型'"
      width="520px"
      append-to-body
      @closed="resetDialog"
    >
      <el-form ref="formRef" :model="dialog.form" :rules="dialogRules" label-width="90px">
        <el-form-item label="审批编码" prop="approvalCode">
          <el-input
            v-model="dialog.form.approvalCode"
            placeholder="如：PURCHASE"
            :disabled="dialog.isEdit"
            maxlength="50"
          />
        </el-form-item>
        <el-form-item label="审批名称" prop="approvalName">
          <el-input v-model="dialog.form.approvalName" placeholder="如：采购审批" maxlength="50" />
        </el-form-item>
        <el-form-item label="描述" prop="description">
          <el-input
            v-model="dialog.form.description"
            type="textarea"
            :rows="3"
            placeholder="请输入审批类型描述"
            maxlength="200"
            show-word-limit
          />
        </el-form-item>
        <el-form-item label="绑定表单">
          <el-select
            v-model="dialog.form.currentFormVersionId"
            placeholder="请选择已发布的表单版本"
            filterable
            style="width: 100%"
          >
            <el-option
              v-for="option in formVersionOptions"
              :key="option.value"
              :label="option.label"
              :value="option.value"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="绑定流程">
          <el-select
            v-model="dialog.form.currentFlowVersionId"
            placeholder="请选择已发布的流程版本"
            filterable
            style="width: 100%"
          >
            <el-option
              v-for="option in flowVersionOptions"
              :key="option.value"
              :label="option.label"
              :value="option.value"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="允许撤回">
          <el-switch v-model="dialog.form.allowWithdraw" />
        </el-form-item>
        <el-form-item label="允许取消">
          <el-switch v-model="dialog.form.allowCancel" />
        </el-form-item>
      </el-form>
      <template #footer>
        <div class="dialog-footer">
          <el-button type="primary" :loading="saving" @click="handleSave">确 定</el-button>
          <el-button @click="dialog.visible = false">取 消</el-button>
        </div>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts" name="WorkflowApprovalType">
import {
  addApprovalDefinition,
  deleteApprovalDefinition,
  disableApprovalDefinition,
  listApprovalDefinition,
  publishApprovalDefinition,
  updateApprovalDefinition
} from '@/api/workflow/definition'
import { listForm, listFormVersion } from '@/api/workflow/form'
import { listWorkflow, listWorkflowVersion } from '@/api/workflow/workflow'
import type { WfApprovalDefinition } from '@/types'

const router = useRouter()
const { proxy } = getCurrentInstance() as any

const loading = ref(true)
const saving = ref(false)
const rows = ref<WfApprovalDefinition[]>([])
const formRef = ref()

/** 版本ID → 编码_v版本号（对齐设计稿的 leave_form_v2 形式） */
const formVersionLabels = ref(new Map<string, string>())
const flowVersionLabels = ref(new Map<string, string>())
const flowVersionWorkflow = ref(new Map<string, { id: string; name: string }>())
/** 弹窗可选：仅已发布版本（后端发布校验要求 PUBLISHED） */
const formVersionOptions = ref<Array<{ label: string; value: string }>>([])
const flowVersionOptions = ref<Array<{ label: string; value: string }>>([])

const dialog = reactive({
  visible: false,
  isEdit: false,
  form: {
    id: '',
    approvalCode: '',
    approvalName: '',
    description: '',
    currentFormVersionId: '',
    currentFlowVersionId: '',
    allowWithdraw: true,
    allowCancel: true
  }
})

const dialogRules = {
  approvalCode: [{ required: true, message: '请输入审批编码', trigger: 'blur' }],
  approvalName: [{ required: true, message: '请输入审批名称', trigger: 'blur' }]
}

function statusLabel(status?: string) {
  if (status === 'PUBLISHED') return '已发布'
  if (status === 'DISABLED') return '已停用'
  return '草稿'
}

function formLabel(versionId?: string) {
  if (!versionId) return '—'
  return formVersionLabels.value.get(versionId) || versionId
}

function flowLabel(versionId?: string) {
  if (!versionId) return '—'
  return flowVersionLabels.value.get(versionId) || versionId
}

async function getList() {
  loading.value = true
  try {
    const res = await listApprovalDefinition()
    rows.value = res.data || []
  } catch {
    rows.value = []
  } finally {
    loading.value = false
  }
  loadBindingLabels()
}

/** 解析表单/流程版本的可读名称（接口只返回版本ID） */
async function loadBindingLabels() {
  formVersionLabels.value = new Map()
  flowVersionLabels.value = new Map()
  flowVersionWorkflow.value = new Map()
  formVersionOptions.value = []
  flowVersionOptions.value = []
  try {
    const formRes = await listForm()
    const forms = formRes.data || []
    await Promise.all(
      forms.map(async form => {
        try {
          const versionRes = await listFormVersion(form.id)
          ;(versionRes.data || []).forEach(version => {
            const code = form.formCode || form.formName || form.id
            const label = `${code}_v${version.versionNo ?? ''}`
            formVersionLabels.value.set(version.id, label)
            if (version.status === 'PUBLISHED') {
              formVersionOptions.value.push({ label, value: version.id })
            }
          })
        } catch {
          /* 单个表单版本读取失败不影响列表 */
        }
      })
    )
  } catch {
    /* 忽略 */
  }

  try {
    const workflowRes = await listWorkflow()
    const workflows = workflowRes.data || []
    await Promise.all(
      workflows.map(async workflow => {
        try {
          const versionRes = await listWorkflowVersion(workflow.id)
          ;(versionRes.data || []).forEach(version => {
            const code = workflow.workflowCode || workflow.workflowName || workflow.id
            const label = `${code}_v${version.versionNo ?? ''}`
            flowVersionLabels.value.set(version.id, label)
            if (version.status === 'PUBLISHED') {
              flowVersionOptions.value.push({ label, value: version.id })
            }
            flowVersionWorkflow.value.set(version.id, {
              id: workflow.id,
              name: workflow.workflowName || workflow.workflowCode || workflow.id
            })
          })
        } catch {
          /* 忽略 */
        }
      })
    )
  } catch {
    /* 忽略 */
  }
}

function openCreate() {
  dialog.isEdit = false
  dialog.visible = true
}

function openEdit(row: WfApprovalDefinition) {
  if (row.status !== 'DRAFT') {
    proxy.$modal.msgWarning('仅草稿状态的审批类型可编辑配置')
    return
  }
  dialog.isEdit = true
  dialog.form.id = row.id
  dialog.form.approvalCode = row.approvalCode || ''
  dialog.form.approvalName = row.approvalName || ''
  dialog.form.description = row.description || ''
  dialog.form.currentFormVersionId = row.currentFormVersionId || ''
  dialog.form.currentFlowVersionId = row.currentFlowVersionId || ''
  dialog.form.allowWithdraw = row.allowWithdraw !== false
  dialog.form.allowCancel = row.allowCancel !== false
  dialog.visible = true
}

function resetDialog() {
  dialog.form.id = ''
  dialog.form.approvalCode = ''
  dialog.form.approvalName = ''
  dialog.form.description = ''
  dialog.form.currentFormVersionId = ''
  dialog.form.currentFlowVersionId = ''
  dialog.form.allowWithdraw = true
  dialog.form.allowCancel = true
  saving.value = false
  formRef.value?.resetFields()
}

async function handleSave() {
  const valid = await formRef.value?.validate().catch(() => false)
  if (!valid) return
  saving.value = true
  try {
    if (dialog.isEdit) {
      await updateApprovalDefinition(dialog.form.id, {
        approvalName: dialog.form.approvalName,
        description: dialog.form.description,
        currentFormVersionId: dialog.form.currentFormVersionId || undefined,
        currentFlowVersionId: dialog.form.currentFlowVersionId || undefined,
        allowWithdraw: dialog.form.allowWithdraw,
        allowCancel: dialog.form.allowCancel
      })
      proxy.$modal.msgSuccess('修改成功')
    } else {
      const res = await addApprovalDefinition({
        approvalCode: dialog.form.approvalCode.trim().toUpperCase(),
        approvalName: dialog.form.approvalName,
        description: dialog.form.description
      })
      // 创建接口不接收绑定字段，新建后补一次更新（新记录为 DRAFT，后端允许修改）
      const createdId: string = (res as any)?.data?.id || ''
      if (createdId && (dialog.form.currentFormVersionId || dialog.form.currentFlowVersionId)) {
        await updateApprovalDefinition(createdId, {
          currentFormVersionId: dialog.form.currentFormVersionId || undefined,
          currentFlowVersionId: dialog.form.currentFlowVersionId || undefined
        })
      }
      proxy.$modal.msgSuccess('创建成功')
    }
    dialog.visible = false
    await getList()
  } finally {
    saving.value = false
  }
}

async function handlePublish(row: WfApprovalDefinition) {
  try {
    await proxy.$modal.confirm(`确认发布审批类型「${row.approvalName || row.approvalCode}」？`)
  } catch {
    return
  }
  try {
    await publishApprovalDefinition(row.id)
    proxy.$modal.msgSuccess('发布成功')
    await getList()
  } catch {
    /* 响应拦截器已提示 */
  }
}

/**
 * 删除审批类型（P8：真实软删除）。
 * 已产生审批单的类型后端拒绝删除（保留历史），此时改为停用。
 */
async function handleDelete(row: WfApprovalDefinition) {
  try {
    await proxy.$modal.confirm(
      `确认删除审批类型「${row.approvalName || row.approvalCode}」？删除后不可恢复，已产生的审批单不受影响。`
    )
  } catch {
    return
  }
  try {
    await deleteApprovalDefinition(row.id)
    proxy.$modal.msgSuccess('删除成功')
  } catch (error: any) {
    const msg = String(error?.message || '')
    // 已有审批单 → 引导停用（保留历史数据）
    if (msg.includes('不可删除')) {
      try {
        await proxy.$modal.confirm('该审批类型已产生审批单，为保证历史数据完整不可删除。是否改为停用？')
      } catch {
        return
      }
      await disableApprovalDefinition(row.id)
      proxy.$modal.msgSuccess('已停用')
    } else {
      return
    }
  }
  await getList()
}

/** 打开流程设计器：优先按已绑定流程版本定位工作流 */
function openDesigner(row: WfApprovalDefinition) {
  const versionId = row.currentFlowVersionId
  const workflow = versionId ? flowVersionWorkflow.value.get(versionId) : undefined
  if (workflow) {
    router.push({
      path: '/workflow/designer',
      query: { workflowId: workflow.id, name: workflow.name, definitionId: row.id }
    })
    return
  }
  router.push({
    path: '/workflow/designer',
    query: { definitionId: row.id, name: row.approvalName || row.approvalCode || '流程' }
  })
}

getList()
</script>

<style scoped lang="scss">
.wf-type__header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 16px;
}

.wf-type__title {
  margin: 0;
  font-size: 18px;
  font-weight: 600;
  color: #303133;
}

.wf-binding {
  color: #606266;
  font-size: 14px;
}

.wf-binding--link {
  border-bottom: 1px dashed transparent;
}

.wf-binding--link:hover {
  color: var(--el-color-primary);
  border-bottom-color: var(--el-color-primary);
  cursor: pointer;
}

.wf-type-status {
  font-size: 14px;
  color: #606266;

  &--PUBLISHED {
    color: #67c23a;
  }

  &--DISABLED {
    color: #909399;
  }

  &--DRAFT {
    color: #e6a23c;
  }
}

.wf-op {
  color: #606266;
  font-size: 14px;
  cursor: pointer;
}

.wf-op:hover {
  color: var(--el-color-primary);
}

.wf-op-sep {
  margin: 0 8px;
  color: #dcdfe6;
}
</style>
