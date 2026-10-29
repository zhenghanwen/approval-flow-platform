<template>
  <div class="app-container">
    <div class="wf-type__header">
      <h2 class="wf-type__title">流程管理</h2>
      <el-button type="primary" icon="Plus" @click="openCreate">新建流程</el-button>
    </div>

    <div class="wf-table-card">
      <el-table v-loading="loading" class="wf-table" :data="rows" row-key="id">
        <el-table-column label="编码" prop="workflowCode" min-width="160" show-overflow-tooltip />
        <el-table-column label="名称" prop="workflowName" min-width="200" show-overflow-tooltip />
        <el-table-column label="当前版本" min-width="110">
          <template #default="{ row }">
            <span>{{ versionLabel(row) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="状态" min-width="100">
          <template #default="{ row }">
            <span :class="['wf-type-status', `wf-type-status--${row.status}`]">{{ statusLabel(row.status) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="创建人" prop="createdBy" min-width="110" show-overflow-tooltip />
        <el-table-column label="创建时间" prop="createdAt" min-width="180" show-overflow-tooltip />
        <el-table-column label="操作" width="140">
          <template #default="{ row }">
            <span class="wf-op" @click="openDesigner(row)">设计</span>
            <span class="wf-op-sep">|</span>
            <span class="wf-op" @click="openVersions(row)">版本</span>
          </template>
        </el-table-column>
        <template #empty>
          <el-empty description="暂无流程，点击右上角「新建流程」创建" :image-size="90" />
        </template>
      </el-table>
    </div>

    <!-- 新建流程 -->
    <el-dialog
      v-model="createDialog.visible"
      title="新建流程"
      width="480px"
      append-to-body
      @closed="resetCreate"
    >
      <el-form ref="createRef" :model="createDialog.form" :rules="createRules" label-width="90px">
        <el-form-item label="流程编码" prop="workflowCode">
          <el-input v-model="createDialog.form.workflowCode" placeholder="如：PURCHASE_FLOW" maxlength="50" />
        </el-form-item>
        <el-form-item label="流程名称" prop="workflowName">
          <el-input v-model="createDialog.form.workflowName" placeholder="如：采购审批流程" maxlength="50" />
        </el-form-item>
      </el-form>
      <template #footer>
        <div class="dialog-footer">
          <el-button type="primary" :loading="saving" @click="handleCreate">确 定</el-button>
          <el-button @click="createDialog.visible = false">取 消</el-button>
        </div>
      </template>
    </el-dialog>

    <!-- 版本管理 -->
    <el-dialog
      v-model="versionDialog.visible"
      :title="`版本管理 - ${versionDialog.rowName}`"
      width="640px"
      append-to-body
    >
      <el-table v-loading="versionDialog.loading" class="wf-table" :data="versionDialog.rows" row-key="id">
        <el-table-column label="版本号" width="90">
          <template #default="{ row }">
            v{{ row.versionNo }}
          </template>
        </el-table-column>
        <el-table-column label="状态" min-width="110">
          <template #default="{ row }">
            <span :class="['wf-type-status', `wf-type-status--${row.status}`]">{{ versionStatusLabel(row.status) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="发布时间" prop="publishedAt" min-width="180" show-overflow-tooltip />
        <el-table-column label="发布人" prop="publishedBy" min-width="100" show-overflow-tooltip />
        <el-table-column label="说明" min-width="140">
          <template #default>
            <span class="wf-light">在流程设计器中发布</span>
          </template>
        </el-table-column>
        <template #empty>
          <el-empty description="暂无版本，请先在「设计」中保存并发布" :image-size="80" />
        </template>
      </el-table>
    </el-dialog>
  </div>
</template>

<script setup lang="ts" name="WorkflowFlows">
import { addWorkflow, listWorkflow, listWorkflowVersion } from '@/api/workflow/workflow'
import type { WfWorkflowDefinition, WfWorkflowVersion } from '@/types'

const router = useRouter()
const { proxy } = getCurrentInstance() as any

const loading = ref(true)
const saving = ref(false)
const rows = ref<WfWorkflowDefinition[]>([])

const createRef = ref()
const createDialog = reactive({
  visible: false,
  form: { workflowCode: '', workflowName: '' }
})
const createRules = {
  workflowCode: [{ required: true, message: '请输入流程编码', trigger: 'blur' }],
  workflowName: [{ required: true, message: '请输入流程名称', trigger: 'blur' }]
}

const versionDialog = reactive({
  visible: false,
  rowId: '',
  rowName: '',
  loading: false,
  rows: [] as WfWorkflowVersion[]
})

function statusLabel(status?: string) {
  if (status === 'PUBLISHED') return '已发布'
  if (status === 'DISABLED') return '已停用'
  return '草稿'
}

function versionStatusLabel(status?: string) {
  if (status === 'PUBLISHED') return '已发布'
  if (status === 'ARCHIVED') return '已归档'
  return '草稿'
}

function versionLabel(row: WfWorkflowDefinition) {
  return row.currentVersion != null ? `v${row.currentVersion}` : '—'
}

async function getList() {
  loading.value = true
  try {
    const res = await listWorkflow()
    rows.value = res.data || []
  } catch {
    rows.value = []
  } finally {
    loading.value = false
  }
}

function openCreate() {
  createDialog.visible = true
}

function resetCreate() {
  createDialog.form.workflowCode = ''
  createDialog.form.workflowName = ''
  saving.value = false
  createRef.value?.resetFields()
}

async function handleCreate() {
  const valid = await createRef.value?.validate().catch(() => false)
  if (!valid) return
  saving.value = true
  try {
    const res = await addWorkflow({
      workflowCode: createDialog.form.workflowCode.trim().toUpperCase(),
      workflowName: createDialog.form.workflowName
    })
    proxy.$modal.msgSuccess('创建成功')
    createDialog.visible = false
    await getList()
    // 新建后直接进入流程设计器编排（对齐「新建流程 → 进流程设计器」）
    const id = (res as any)?.data?.id
    if (id) {
      router.push({ path: '/workflow/designer', query: { workflowId: id, name: createDialog.form.workflowName } })
    }
  } finally {
    saving.value = false
  }
}

/** 进入流程设计器（校验/发布在设计器内完成） */
function openDesigner(row: WfWorkflowDefinition) {
  router.push({
    path: '/workflow/designer',
    query: { workflowId: row.id, name: row.workflowName || row.workflowCode || '流程' }
  })
}

async function openVersions(row: WfWorkflowDefinition) {
  versionDialog.rowId = row.id
  versionDialog.rowName = row.workflowName || row.workflowCode || row.id
  versionDialog.visible = true
  versionDialog.loading = true
  try {
    const res = await listWorkflowVersion(row.id)
    versionDialog.rows = (res.data || []).slice().sort((a, b) => (b.versionNo || 0) - (a.versionNo || 0))
  } catch {
    versionDialog.rows = []
  } finally {
    versionDialog.loading = false
  }
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

  &:hover {
    color: var(--el-color-primary);
  }
}

.wf-op-sep {
  margin: 0 8px;
  color: #dcdfe6;
}

.wf-light {
  color: #c0c4cc;
}
</style>
