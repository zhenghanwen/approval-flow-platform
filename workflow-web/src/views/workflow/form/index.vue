<template>
  <div class="app-container">
    <div class="wf-panel wf-form-card" v-loading="loading">
      <h2 class="wf-form__title">{{ meta.pageTitle }}</h2>

      <el-form
        ref="formRef"
        class="wf-form"
        :model="form"
        :rules="rules"
        label-width="130px"
        label-position="right"
        hide-required-asterisk
        :validate-on-rule-change="false"
      >
        <el-form-item label="审批标题" prop="title">
          <el-input v-model="form.title" placeholder="请输入审批标题" clearable />
        </el-form-item>

        <el-form-item label="申请人">
          <el-input :model-value="applicantName" disabled />
        </el-form-item>
        <el-form-item label="所属部门">
          <el-input :model-value="deptName" disabled />
        </el-form-item>
        <el-form-item label="抄送人">
          <el-select
            v-model="ccUserIds"
            multiple
            filterable
            collapse-tags
            collapse-tags-tooltip
            clearable
            placeholder="选择抄送人（选填，可多选）"
            style="width: 100%"
          >
            <el-option
              v-for="user in ccUserOptions"
              :key="user.userId"
              :label="user.nickName || user.userName"
              :value="String(user.userId)"
              :disabled="String(user.userId) === String(userStore.id)"
            />
          </el-select>
        </el-form-item>

        <template v-for="field in renderFields" :key="field.key">
          <el-form-item :label="field.label" :prop="field.key">
            <el-input
              v-if="field.type === 'input'"
              v-model="form[field.key]"
              :placeholder="field.placeholder"
              clearable
            />

            <el-input
              v-else-if="field.type === 'textarea'"
              v-model="form[field.key]"
              type="textarea"
              :rows="field.rows || 4"
              :placeholder="field.placeholder"
              maxlength="500"
              show-word-limit
            />

            <el-input
              v-else-if="field.type === 'number'"
              v-model="form[field.key]"
              :placeholder="field.placeholder || '请输入金额'"
              inputmode="decimal"
            >
              <template v-if="field.prefix" #prefix>{{ field.prefix }}</template>
              <template v-if="field.suffix" #suffix>{{ field.suffix }}</template>
            </el-input>

            <el-date-picker
              v-else-if="field.type === 'date'"
              v-model="form[field.key]"
              type="date"
              value-format="YYYY-MM-DD"
              placeholder="请选择日期"
              style="width: 100%"
            />

            <el-select
              v-else-if="field.type === 'select'"
              v-model="form[field.key]"
              :multiple="field.multiple"
              :placeholder="field.placeholder || `请选择${field.label}`"
              clearable
              style="width: 100%"
            >
              <el-option
                v-for="option in field.options || []"
                :key="option.value"
                :label="option.label"
                :value="option.value"
              />
            </el-select>

            <el-switch
              v-else-if="field.type === 'switch'"
              v-model="form[field.key]"
              :active-text="form[field.key] ? '是' : '否'"
            />
          </el-form-item>
        </template>

        <el-form-item label="附件上传">
          <div class="wf-upload">
            <el-upload
              :auto-upload="false"
              :show-file-list="false"
              multiple
              :on-change="handleUploadChange"
            >
              <div class="wf-upload__box">
                <el-icon><Upload /></el-icon>
                <span>点击上传</span>
              </div>
            </el-upload>
            <ul v-if="uploadFiles.length" class="wf-upload__list">
              <li v-for="file in uploadFiles" :key="file.uid">
                <span class="wf-upload__name">{{ file.name }}</span>
                <el-button link type="danger" @click="handleUploadRemove(file)">删除</el-button>
              </li>
            </ul>
          </div>
        </el-form-item>

        <el-form-item label-width="130px" class="wf-form__footer">
          <el-button type="primary" icon="Check" :loading="submitting" @click="handleSubmit">提 交</el-button>
          <el-button :loading="savingDraft" @click="handleSaveDraft">保存草稿</el-button>
          <el-button @click="handleCancel">取 消</el-button>
        </el-form-item>
      </el-form>
    </div>
  </div>
</template>

<script setup lang="ts" name="WorkflowForm">
import { getApprovalDefinition, listApprovalDefinition } from '@/api/workflow/definition'
import { submitApproval } from '@/api/workflow/approval'
import { saveFormDraft } from '@/api/workflow/form'
import { getUserProfile, listUser } from '@/api/system/user'
import useUserStore from '@/store/modules/user'
import type { WfApprovalDefinition } from '@/types'
import { getFormMeta, schemaToWfFields, type WfField, type WfFormMeta } from '../fields'
import { loadSchemaByVersionId } from '../schema'

const route = useRoute()
const router = useRouter()
const { proxy } = getCurrentInstance() as any
const userStore = useUserStore()

const code = String(route.query.code || 'PURCHASE').toUpperCase()
const templateMeta = getFormMeta(code)
/** 后端 schema 里由表单顶部固定展示、不再重复渲染的字段 */
const SCHEMA_SKIP_KEYS = ['title', 'applicant', 'applicantId', 'dept', 'deptId', 'deptName', 'attachment']
/** 后端表单 schema 渲染出的字段（为空时回落到本地模板） */
const dynamicFields = ref<WfField[]>([])

const loading = ref(false)
const submitting = ref(false)
const savingDraft = ref(false)
const definition = ref<WfApprovalDefinition | null>(null)
const formRef = ref()
const uploadFiles = ref<any[]>([])
const applicantName = ref(userStore.nickName || userStore.name || '')
const deptName = ref('')
const deptId = ref('')
/** 后端 schema 的全部字段 key（含顶部固定展示的字段，提交时需要补值） */
const schemaKeys = ref<string[]>([])
/** 抄送人（P7）：选填，提交时写入 wf_approval_cc */
const ccUserIds = ref<string[]>([])
const ccUserOptions = ref<any[]>([])

async function loadCcUsers() {
  try {
    const res = await listUser({ pageNum: 1, pageSize: 1000 })
    ccUserOptions.value = (res as any)?.rows || []
  } catch {
    ccUserOptions.value = []
  }
}

const meta = computed<WfFormMeta>(() => {
  const fields = dynamicFields.value.length
    ? [templateMeta.fields[0], ...dynamicFields.value]
    : templateMeta.fields
  return {
    ...templateMeta,
    pageTitle: definition.value?.approvalName
      ? `${definition.value.approvalName}申请`
      : templateMeta.pageTitle,
    fields
  }
})

const form = reactive<Record<string, any>>({})

/** 表单主体字段：首个字段是标题（已固定渲染），附件上传单独固定渲染 */
const renderFields = computed(() =>
  meta.value.fields.slice(1).filter(field => field.type !== 'upload')
)

function initFormValues() {
  meta.value.fields.forEach(field => {
    if (form[field.key] === undefined) {
      form[field.key] = field.type === 'switch' ? false : field.multiple ? [] : ''
    }
  })
}
initFormValues()

const rules = computed(() => {
  const result: Record<string, any> = {}
  meta.value.fields.forEach(field => {
    if (field.required) {
      result[field.key] = [
        {
          required: true,
          message: `请填写${field.label}`,
          trigger: field.type === 'select' || field.type === 'switch' ? 'change' : 'blur'
        }
      ]
    }
  })
  return result
})

/** 按审批类型解析绑定的表单版本 */
async function loadDefinition() {
  const queryId = String(route.query.definitionId || '')
  loading.value = true
  try {
    if (queryId) {
      const res = await getApprovalDefinition(queryId)
      definition.value = res.data || null
    }
    if (!definition.value) {
      const res = await listApprovalDefinition()
      definition.value =
        (res.data || []).find(
          item => (item.approvalCode || '').toUpperCase() === code
        ) || null
    }
    // 有绑定表单版本时按后端 schema 渲染（MongoDB 读取失败则保持模板）
    if (definition.value?.currentFormVersionId) {
      const schemaFields = await loadSchemaByVersionId(definition.value.currentFormVersionId)
      if (schemaFields.length) {
        schemaKeys.value = schemaFields.map(field => field.field)
        // 标题 / 申请人 / 部门在表单顶部固定展示，不再重复渲染
        dynamicFields.value = schemaToWfFields(
          schemaFields.filter(field => !SCHEMA_SKIP_KEYS.includes(field.field))
        )
        initFormValues()
      }
    }
  } catch {
    definition.value = null
  } finally {
    loading.value = false
  }
}

/** 申请人 / 部门取当前登录用户 */
async function loadApplicant() {
  try {
    const res = await getUserProfile()
    const profile: any = res.data || {}
    applicantName.value = profile.nickName || applicantName.value
    deptName.value = profile.dept?.deptName || ''
    deptId.value = String(profile.dept?.deptId || '')
  } catch {
    deptName.value = ''
    deptId.value = ''
  }
}

function handleUploadChange(file: any) {
  const name = file?.name
  if (!name) return
  if (!uploadFiles.value.some(item => item.uid === file.uid)) {
    uploadFiles.value.push(file)
  }
  form.attachment = uploadFiles.value.map(item => item.name).join(',')
}

function handleUploadRemove(file: any) {
  uploadFiles.value = uploadFiles.value.filter(item => item.uid !== file.uid && item.name !== file.name)
  form.attachment = uploadFiles.value.map(item => item.name).join(',')
}

/** 表单值转提交数据（数字字段转 Number） */
function buildFormData() {
  const payload: Record<string, any> = {}
  meta.value.fields.forEach(field => {
    if (field.type === 'upload') return
    const value = form[field.key]
    if (field.type === 'number') {
      payload[field.key] = value === '' || value === null || value === undefined ? null : Number(value)
    } else {
      payload[field.key] = value
    }
  })
  // 申请人 / 部门在表单顶部展示，但后端 schema 里是必填项，需要一并提交
  const userId = String(userStore.id || '')
  const hiddenValues: Record<string, any> = {
    applicant: userId,
    applicantId: userId,
    dept: deptId.value,
    deptId: deptId.value,
    deptName: deptName.value
  }
  Object.keys(hiddenValues).forEach(key => {
    if (schemaKeys.value.includes(key) && !(key in payload)) {
      payload[key] = hiddenValues[key]
    }
  })
  if (form.attachment) payload.attachment = form.attachment
  return payload
}

async function ensureDefinition(): Promise<boolean> {
  if (!definition.value?.id) {
    proxy.$modal.msgWarning('未找到审批类型，请先在「审批类型配置」中创建并发布')
    return false
  }
  if (!definition.value.currentFormVersionId) {
    proxy.$modal.msgWarning('该审批类型未绑定已发布的表单版本，暂不能提交')
    return false
  }
  return true
}

async function handleSubmit() {
  const valid = await formRef.value?.validate().catch(() => false)
  if (!valid) return
  if (!(await ensureDefinition())) return
  submitting.value = true
  try {
    const res = await submitApproval({
      approvalDefinitionId: definition.value!.id,
      formVersionId: definition.value!.currentFormVersionId!,
      title: form.title,
      formData: buildFormData(),
      ccUserIds: ccUserIds.value.length ? ccUserIds.value : undefined
    })
    proxy.$modal.msgSuccess('提交成功')
    const instance: any = (res as any)?.data
    if (instance?.id) {
      router.replace(`/workflow/detail/${instance.id}`)
    } else {
      router.push('/workflow/mine')
    }
  } finally {
    submitting.value = false
  }
}

async function handleSaveDraft() {
  if (!definition.value?.currentFormVersionId) {
    proxy.$modal.msgWarning('该审批类型未绑定已发布的表单版本，暂不能保存草稿')
    return
  }
  savingDraft.value = true
  try {
    await saveFormDraft({
      formVersionId: definition.value.currentFormVersionId,
      data: buildFormData(),
      title: form.title || meta.value.name
    })
    proxy.$modal.msgSuccess('草稿已保存')
  } finally {
    savingDraft.value = false
  }
}

function handleCancel() {
  router.back()
}

loadDefinition()
loadApplicant()
loadCcUsers()
</script>

<style scoped lang="scss">
.wf-form-card {
  min-height: 600px;
  padding: 24px 28px 8px;
}

.wf-form__title {
  margin: 0 0 28px;
  font-size: 18px;
  font-weight: 600;
  color: #303133;
}

.wf-form {
  max-width: 760px;
}

.wf-form :deep(.el-form-item) {
  margin-bottom: 22px;
}

.wf-form :deep(.el-form-item__label) {
  justify-content: flex-start;
  padding-right: 16px;
  color: #606266;
  font-size: 14px;
}

.wf-form :deep(.el-form-item__content) {
  max-width: 500px;
}

.wf-form :deep(.el-input__wrapper),
.wf-form :deep(.el-textarea__inner) {
  font-size: 14px;
}

.wf-upload {
  width: 100%;
}

.wf-upload__list {
  margin: 8px 0 0;
  padding: 0;
  list-style: none;

  li {
    display: flex;
    align-items: center;
    gap: 8px;
    height: 26px;
    font-size: 13px;
    color: #606266;
  }
}

.wf-upload__name {
  max-width: 360px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.wf-upload__box {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 6px;
  width: 120px;
  height: 86px;
  border: 1px dashed #dcdfe6;
  border-radius: 4px;
  color: #909399;
  font-size: 13px;
  background: #fff;
  transition: border-color 0.2s, color 0.2s;

  &:hover {
    border-color: var(--el-color-primary);
    color: var(--el-color-primary);
  }
}

.wf-form__footer {
  margin-top: 34px;
}

.wf-form__footer :deep(.el-form-item__content) {
  gap: 12px;
}
</style>
