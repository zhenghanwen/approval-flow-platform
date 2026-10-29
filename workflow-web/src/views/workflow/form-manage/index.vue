<template>
  <div class="app-container">
    <div class="wf-type__header">
      <h2 class="wf-type__title">表单管理</h2>
      <el-button type="primary" icon="Plus" @click="openCreate">新建表单</el-button>
    </div>

    <div class="wf-table-card">
      <el-table v-loading="loading" class="wf-table" :data="rows" row-key="id">
        <el-table-column label="编码" prop="formCode" min-width="160" show-overflow-tooltip />
        <el-table-column label="名称" prop="formName" min-width="200" show-overflow-tooltip />
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
        <el-table-column label="操作" width="180">
          <template #default="{ row }">
            <span class="wf-op" @click="openDesign(row)">设计</span>
            <span class="wf-op-sep">|</span>
            <span class="wf-op" @click="openVersions(row)">版本</span>
            <span class="wf-op-sep">|</span>
            <span class="wf-op" @click="handlePublish(row)">发布</span>
          </template>
        </el-table-column>
        <template #empty>
          <el-empty description="暂无表单，点击右上角「新建表单」创建" :image-size="90" />
        </template>
      </el-table>
    </div>

    <!-- 新建表单 -->
    <el-dialog
      v-model="createDialog.visible"
      title="新建表单"
      width="480px"
      append-to-body
      @closed="resetCreate"
    >
      <el-form ref="createRef" :model="createDialog.form" :rules="createRules" label-width="90px">
        <el-form-item label="表单编码" prop="formCode">
          <el-input v-model="createDialog.form.formCode" placeholder="如：PURCHASE_FORM" maxlength="50" />
        </el-form-item>
        <el-form-item label="表单名称" prop="formName">
          <el-input v-model="createDialog.form.formName" placeholder="如：采购审批表单" maxlength="50" />
        </el-form-item>
      </el-form>
      <template #footer>
        <div class="dialog-footer">
          <el-button type="primary" :loading="saving" @click="handleCreate">确 定</el-button>
          <el-button @click="createDialog.visible = false">取 消</el-button>
        </div>
      </template>
    </el-dialog>

    <!-- 表单设计（schema 编辑） -->
    <el-dialog
      v-model="designDialog.visible"
      :title="`表单设计 - ${designDialog.rowName}`"
      width="640px"
      append-to-body
      @closed="designDialog.schemaText = ''"
    >
      <p class="wf-schema-tip">
        编辑表单字段 JSON（字段结构：{ field, label, type, required, placeholder, options }）。保存草稿后可在版本管理中发布。
      </p>
      <el-input
        v-model="designDialog.schemaText"
        type="textarea"
        :rows="14"
        class="wf-schema-editor"
        placeholder="请输入表单 schema JSON"
      />
      <template #footer>
        <div class="dialog-footer">
          <el-button :loading="designDialog.saving" @click="handleSaveDraft">保存草稿</el-button>
          <el-button type="primary" :loading="designDialog.publishing" @click="handleSaveAndPublish">保存并发布</el-button>
          <el-button @click="designDialog.visible = false">取 消</el-button>
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
        <el-table-column label="操作" width="90">
          <template #default="{ row }">
            <span v-if="row.status === 'DRAFT'" class="wf-op" @click="handlePublishVersion(row)">发布</span>
            <span v-else class="wf-light">—</span>
          </template>
        </el-table-column>
        <template #empty>
          <el-empty description="暂无版本，请先在「设计」中保存草稿" :image-size="80" />
        </template>
      </el-table>
    </el-dialog>
  </div>
</template>

<script setup lang="ts" name="WorkflowForms">
import { createForm, getFormSchema, listForm, listFormVersion, publishFormVersion, saveFormSchemaDraft } from '@/api/workflow/form'
import { extractSchemaFields } from '../schema'
import type { WfFormDefinition, WfFormVersion } from '@/types'

const { proxy } = getCurrentInstance() as any

const loading = ref(true)
const saving = ref(false)
const rows = ref<WfFormDefinition[]>([])

const createRef = ref()
const createDialog = reactive({
  visible: false,
  form: { formCode: '', formName: '' }
})
const createRules = {
  formCode: [{ required: true, message: '请输入表单编码', trigger: 'blur' }],
  formName: [{ required: true, message: '请输入表单名称', trigger: 'blur' }]
}

const designDialog = reactive({
  visible: false,
  rowId: '',
  rowName: '',
  schemaText: '',
  saving: false,
  publishing: false
})

const versionDialog = reactive({
  visible: false,
  rowId: '',
  rowName: '',
  loading: false,
  rows: [] as WfFormVersion[]
})

/** 无已发布 schema 时用于新建草稿的默认模板（对齐 fields.ts 字段结构） */
const DEFAULT_SCHEMA = [
  { field: 'title', label: '审批标题', type: 'text', required: true, placeholder: '请输入审批标题' },
  { field: 'reason', label: '申请事由', type: 'textarea', required: true, placeholder: '请填写申请事由' },
  { field: 'amount', label: '金额', type: 'money', required: false, placeholder: '请输入金额' },
  { field: 'remark', label: '备注', type: 'text', required: false, placeholder: '选填' }
]

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

function versionLabel(row: WfFormDefinition) {
  return row.currentVersion != null ? `v${row.currentVersion}` : '—'
}

async function getList() {
  loading.value = true
  try {
    const res = await listForm()
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
  createDialog.form.formCode = ''
  createDialog.form.formName = ''
  saving.value = false
  createRef.value?.resetFields()
}

async function handleCreate() {
  const valid = await createRef.value?.validate().catch(() => false)
  if (!valid) return
  saving.value = true
  try {
    await createForm({
      formCode: createDialog.form.formCode.trim().toUpperCase(),
      formName: createDialog.form.formName
    })
    proxy.$modal.msgSuccess('创建成功')
    createDialog.visible = false
    await getList()
  } finally {
    saving.value = false
  }
}

async function openDesign(row: WfFormDefinition) {
  designDialog.rowId = row.id
  designDialog.rowName = row.formName || row.formCode || row.id
  let fields: any[] = []
  try {
    const res = await getFormSchema(row.id)
    fields = extractSchemaFields((res as any)?.data)
  } catch {
    fields = []
  }
  designDialog.schemaText = JSON.stringify(fields.length ? fields : DEFAULT_SCHEMA, null, 2)
  designDialog.visible = true
}

function parseSchemaText(): any[] | null {
  try {
    const parsed = JSON.parse(designDialog.schemaText)
    if (!Array.isArray(parsed)) {
      proxy.$modal.msgError('schema 必须是字段数组 JSON')
      return null
    }
    return parsed
  } catch (error: any) {
    proxy.$modal.msgError('schema JSON 格式错误：' + String(error?.message || error))
    return null
  }
}

async function handleSaveDraft() {
  const parsed = parseSchemaText()
  if (!parsed) return
  designDialog.saving = true
  try {
    await saveFormSchemaDraft(designDialog.rowId, parsed)
    proxy.$modal.msgSuccess('草稿已保存')
    await getList()
  } catch {
    /* 响应拦截器已提示 */
  } finally {
    designDialog.saving = false
  }
}

async function handleSaveAndPublish() {
  const parsed = parseSchemaText()
  if (!parsed) return
  designDialog.publishing = true
  try {
    const res = await saveFormSchemaDraft(designDialog.rowId, parsed)
    const versionId = (res as any)?.data?.id
    if (!versionId) {
      proxy.$modal.msgError('保存草稿未返回版本号，发布失败')
      return
    }
    await publishFormVersion(designDialog.rowId, versionId)
    proxy.$modal.msgSuccess('已保存并发布')
    designDialog.visible = false
    await getList()
  } catch {
    /* 响应拦截器已提示 */
  } finally {
    designDialog.publishing = false
  }
}

async function openVersions(row: WfFormDefinition) {
  versionDialog.rowId = row.id
  versionDialog.rowName = row.formName || row.formCode || row.id
  versionDialog.visible = true
  versionDialog.loading = true
  try {
    const res = await listFormVersion(row.id)
    versionDialog.rows = (res.data || []).slice().sort((a, b) => (b.versionNo || 0) - (a.versionNo || 0))
  } catch {
    versionDialog.rows = []
  } finally {
    versionDialog.loading = false
  }
}

/** 行内发布：取最新草稿版本直接发布 */
async function handlePublish(row: WfFormDefinition) {
  try {
    await proxy.$modal.confirm(`确认发布表单「${row.formName || row.formCode}」的最新草稿版本？`)
  } catch {
    return
  }
  try {
    const res = await listFormVersion(row.id)
    const drafts = (res.data || []).filter(item => item.status === 'DRAFT')
    const latest = drafts.sort((a, b) => (b.versionNo || 0) - (a.versionNo || 0))[0]
    if (!latest) {
      proxy.$modal.msgWarning('没有可发布的草稿版本，请先在「设计」中保存草稿')
      return
    }
    await publishFormVersion(row.id, latest.id)
    proxy.$modal.msgSuccess('发布成功')
    await getList()
  } catch {
    /* 响应拦截器已提示 */
  }
}

/** 版本弹窗内发布草稿版本 */
async function handlePublishVersion(row: WfFormVersion) {
  try {
    await proxy.$modal.confirm(`确认发布 v${row.versionNo}？发布后该版本不可再修改。`)
  } catch {
    return
  }
  try {
    await publishFormVersion(versionDialog.rowId, row.id)
    proxy.$modal.msgSuccess('发布成功')
    await openVersions({ id: versionDialog.rowId, formName: versionDialog.rowName } as WfFormDefinition)
    await getList()
  } catch {
    /* 响应拦截器已提示 */
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

.wf-schema-tip {
  margin: 0 0 10px;
  font-size: 12px;
  line-height: 1.6;
  color: #909399;
}

.wf-schema-editor {
  :deep(textarea) {
    font-family: Consolas, Monaco, 'Courier New', monospace;
    font-size: 13px;
  }
}
</style>
