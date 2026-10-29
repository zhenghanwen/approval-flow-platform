<template>
  <el-dialog
    :model-value="modelValue"
    title="表单设计"
    width="94%"
    top="3vh"
    class="wf-fd-dialog"
    append-to-body
    destroy-on-close
    @closed="handleClosed"
    @update:model-value="emit('update:modelValue', $event)"
  >
    <div v-loading="loading" class="wf-fd">
      <!-- 左侧字段类型面板 -->
      <aside class="wf-fd__palette">
        <h4 class="wf-fd__panel-title">字段类型</h4>
        <draggable
          :list="paletteItems"
          :clone="cloneField"
          :sort="false"
          :group="{ name: 'form-fields', pull: 'clone', put: false }"
          item-key="type"
          class="wf-fd__palette-list"
        >
          <template #item="{ element }">
            <div class="wf-fd-palette-item" @click="addField(element.type)">
              <span class="wf-fd-palette-item__icon">{{ element.icon }}</span>
              <div class="wf-fd-palette-item__body">
                <div class="wf-fd-palette-item__label">{{ element.label }}</div>
                <div class="wf-fd-palette-item__desc">{{ element.desc }}</div>
              </div>
            </div>
          </template>
        </draggable>
        <p class="wf-fd__tip">点击或拖拽到中间画布添加字段</p>
      </aside>

      <!-- 中间画布：字段列表（可拖拽排序） -->
      <main class="wf-fd__canvas">
        <div class="wf-fd__canvas-head">
          <h4 class="wf-fd__panel-title">表单字段（{{ fields.length }}）</h4>
          <el-button v-if="fields.length" text size="small" type="danger" @click="clearAll">清空</el-button>
        </div>
        <draggable
          v-model="fields"
          item-key="uid"
          handle=".wf-fd-row__drag"
          :group="{ name: 'form-fields', pull: false, put: true }"
          animation="180"
          class="wf-fd__list"
          @add="onFieldAdded"
        >
          <template #item="{ element }">
            <div
              class="wf-fd-row"
              :class="{ 'is-selected': selectedUid === element.uid }"
              @click="selectField(element)"
            >
              <span class="wf-fd-row__drag" title="拖拽排序">⠿</span>
              <span class="wf-fd-row__icon">{{ typeMeta(element.type)?.icon || '📄' }}</span>
              <div class="wf-fd-row__main">
                <div class="wf-fd-row__label">
                  <span>{{ element.label || '（未命名）' }}</span>
                  <el-tag v-if="element.required" size="small" type="danger" class="wf-fd-row__req">必填</el-tag>
                </div>
                <div class="wf-fd-row__field">
                  <code>{{ element.field || '未设置字段键' }}</code>
                  <span class="wf-fd-row__type">{{ typeLabel(element.type) }}</span>
                </div>
              </div>
              <el-button
                text
                size="small"
                type="danger"
                class="wf-fd-row__del"
                @click.stop="removeField(element)"
              >删除</el-button>
            </div>
          </template>
        </draggable>
        <el-empty v-if="!loading && !fields.length" description="从左侧点击或拖拽添加字段" :image-size="70" />
      </main>

      <!-- 右侧属性面板 -->
      <aside class="wf-fd__props">
        <template v-if="selectedField">
          <h4 class="wf-fd__panel-title">字段属性</h4>
          <div class="wf-fd-props__field">
            <label>字段键 field</label>
            <el-input v-model="selectedField.field" placeholder="英文键，如 amount" @change="validateKey(selectedField)" />
          </div>
          <div class="wf-fd-props__field">
            <label>显示名称 label</label>
            <el-input v-model="selectedField.label" placeholder="如：采购金额" />
          </div>
          <div class="wf-fd-props__field">
            <label>字段类型 type</label>
            <el-select v-model="selectedField.type" style="width: 100%">
              <el-option v-for="t in FIELD_TYPES" :key="t.type" :label="`${t.label}（${t.type}）`" :value="t.type" />
            </el-select>
          </div>
          <div class="wf-fd-props__field">
            <label>必填 required</label>
            <el-switch v-model="selectedField.required" />
          </div>
          <div class="wf-fd-props__field">
            <label>占位提示 placeholder</label>
            <el-input v-model="selectedField.placeholder" placeholder="选填" />
          </div>
          <template v-if="hasOptions(selectedField)">
            <div class="wf-fd-props__field">
              <label>选项 options</label>
              <div v-for="(opt, idx) in selectedField.options" :key="idx" class="wf-fd-props__opt">
                <el-input v-model="opt.label" placeholder="显示文本" />
                <el-input v-model="opt.value" placeholder="值" />
                <el-button text type="danger" size="small" @click="removeOption(selectedField, idx)">×</el-button>
              </div>
              <el-button size="small" class="wf-fd-props__add-opt" @click="addOption(selectedField)">+ 添加选项</el-button>
            </div>
          </template>
          <el-button class="wf-fd-props__delete" type="danger" plain size="small" @click="removeField(selectedField)">
            删除字段
          </el-button>
        </template>
        <el-empty v-else description="选中字段后可编辑属性" :image-size="70" />
      </aside>
    </div>

    <template #footer>
      <div class="dialog-footer">
        <el-button @click="close">取 消</el-button>
        <el-button :loading="savingDraft" @click="handleSaveDraft">保存草稿</el-button>
        <el-button type="primary" :loading="savingPublish" @click="handleSaveAndPublish">保存并发布</el-button>
      </div>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import draggable from 'vuedraggable/dist/vuedraggable.common'
import { getFormSchema, publishFormVersion, saveFormSchemaDraft } from '@/api/workflow/form'
import { extractSchemaFields } from '../schema'

/** 字段类型元数据（对齐 SOP §4.6 的 13 种字段类型） */
interface FieldTypeMeta {
  type: string
  label: string
  icon: string
  desc: string
  defaultLabel?: string
  defaultPlaceholder?: string
}

const FIELD_TYPES: FieldTypeMeta[] = [
  { type: 'text', label: '单行文本', icon: '📝', desc: '简短文本输入', defaultPlaceholder: '请输入' },
  { type: 'textarea', label: '多行文本', icon: '📄', desc: '长文本区域', defaultPlaceholder: '请填写' },
  { type: 'number', label: '数字', icon: '🔢', desc: '数字输入', defaultPlaceholder: '请输入数字' },
  { type: 'money', label: '金额', icon: '💰', desc: '金额（禁止负数）', defaultPlaceholder: '请输入金额' },
  { type: 'date', label: '日期', icon: '📅', desc: '日期选择', defaultPlaceholder: '请选择日期' },
  { type: 'datetime', label: '日期时间', icon: '🕐', desc: '日期 + 时间', defaultPlaceholder: '请选择日期时间' },
  { type: 'select', label: '下拉选择', icon: '🔽', desc: '单选下拉', defaultLabel: '请选择' },
  { type: 'radio', label: '单选', icon: '🔘', desc: '单选按钮' },
  { type: 'checkbox', label: '多选', icon: '☑️', desc: '复选按钮' },
  { type: 'user', label: '用户选择', icon: '👤', desc: '选择用户' },
  { type: 'dept', label: '部门选择', icon: '🏢', desc: '选择部门' },
  { type: 'file', label: '附件上传', icon: '📎', desc: '文件上传' },
  { type: 'boolean', label: '开关', icon: '🔛', desc: '是/否开关' }
]

interface DesignerField {
  uid: string
  field: string
  label: string
  type: string
  required: boolean
  placeholder: string
  options: Array<{ label: string; value: string }>
}

const props = defineProps<{
  modelValue: boolean
  formId: string
  formName: string
}>()

const emit = defineEmits<{
  (e: 'update:modelValue', value: boolean): void
  (e: 'saved'): void
}>()

const { proxy } = getCurrentInstance() as any

const loading = ref(true)
const savingDraft = ref(false)
const savingPublish = ref(false)
const fields = ref<DesignerField[]>([])
const selectedUid = ref('')

const paletteItems = FIELD_TYPES.map(item => ({ ...item }))

const selectedField = computed(() => fields.value.find((field: DesignerField) => field.uid === selectedUid.value) || null)

let uidCounter = 0
function genUid() {
  uidCounter += 1
  return `f_${uidCounter}_${Date.now().toString(36)}`
}

function typeMeta(type: string) {
  return FIELD_TYPES.find(item => item.type === type)
}

function typeLabel(type: string) {
  const meta = typeMeta(type)
  return meta ? `${meta.label}（${type}）` : type
}

function makeField(type: string): DesignerField {
  const meta = typeMeta(type)
  const field: DesignerField = {
    uid: genUid(),
    field: '',
    label: '',
    type,
    required: false,
    placeholder: '',
    options: []
  }
  if (meta?.defaultLabel) {
    field.label = meta.defaultLabel
  }
  if (meta?.defaultPlaceholder) {
    field.placeholder = meta.defaultPlaceholder
  }
  if (type === 'select' || type === 'radio' || type === 'checkbox') {
    field.options = [
      { label: '选项一', value: '1' },
      { label: '选项二', value: '2' }
    ]
  }
  if (type === 'boolean') {
    field.label = '是否'
  }
  return field
}

function cloneField(item: FieldTypeMeta): DesignerField {
  return makeField(item.type)
}

function toDesignerField(item: any): DesignerField {
  return {
    uid: genUid(),
    field: String(item.field || ''),
    label: String(item.label || ''),
    type: String(item.type || 'text'),
    required: !!item.required,
    placeholder: String(item.placeholder || ''),
    options: Array.isArray(item.options)
      ? item.options.map((opt: any) =>
          typeof opt === 'object' && opt !== null
            ? { label: String(opt.label ?? opt.value ?? ''), value: String(opt.value ?? opt.label ?? '') }
            : { label: String(opt ?? ''), value: String(opt ?? '') }
        )
      : []
  }
}

/** 无已发布 schema 时用于新建草稿的默认模板（对齐 fields.ts 字段结构） */
const DEFAULT_SCHEMA = [
  { field: 'title', label: '审批标题', type: 'text', required: true, placeholder: '请输入审批标题' },
  { field: 'reason', label: '申请事由', type: 'textarea', required: true, placeholder: '请填写申请事由' },
  { field: 'amount', label: '金额', type: 'money', required: false, placeholder: '请输入金额' },
  { field: 'remark', label: '备注', type: 'text', required: false, placeholder: '选填' }
]

async function loadSchema() {
  loading.value = true
  try {
    const res = await getFormSchema(props.formId)
    const raw = extractSchemaFields((res as any)?.data)
    fields.value = raw.length ? raw.map(toDesignerField) : DEFAULT_SCHEMA.map(toDesignerField)
  } catch {
    fields.value = DEFAULT_SCHEMA.map(toDesignerField)
  } finally {
    loading.value = false
  }
}

function selectField(field: DesignerField) {
  selectedUid.value = field.uid
}

function addField(type: string) {
  const field = makeField(type)
  fields.value.push(field)
  selectField(field)
}

function onFieldAdded(evt: any) {
  const item = fields.value[evt.newIndex]
  if (item) selectField(item)
}

function removeField(field: DesignerField) {
  fields.value = fields.value.filter((item: DesignerField) => item.uid !== field.uid)
  if (selectedUid.value === field.uid) {
    selectedUid.value = ''
  }
}

function clearAll() {
  proxy.$modal.confirm('确认清空全部字段？').then(() => {
    fields.value = []
    selectedUid.value = ''
  }).catch(() => {})
}

function validateKey(field: DesignerField) {
  const value = (field.field || '').trim()
  if (!value) return
  if (!/^[A-Za-z][A-Za-z0-9_]*$/.test(value)) {
    proxy.$modal.msgWarning('字段键仅支持字母开头，可含字母 / 数字 / 下划线')
    return
  }
  const dup = fields.value.find((item: DesignerField) => item.uid !== field.uid && item.field === value)
  if (dup) {
    proxy.$modal.msgWarning(`字段键 ${value} 已存在`)
  }
}

function hasOptions(field: DesignerField) {
  return ['select', 'radio', 'checkbox'].includes(field.type)
}

function addOption(field: DesignerField) {
  field.options.push({ label: '', value: '' })
}

function removeOption(field: DesignerField, index: number) {
  field.options.splice(index, 1)
}

/** 组装 schema：统一输出 { fields: [...] } 对象格式（后端 FormSchemaValidator 要求） */
function buildSchema(): { fields: any[] } | null {
  const list: any[] = []
  for (const field of fields.value) {
    const key = (field.field || '').trim()
    if (!key) {
      proxy.$modal.msgError('存在未设置字段键（field）的字段，请先在属性面板补全')
      return null
    }
    if (!/^[A-Za-z][A-Za-z0-9_]*$/.test(key)) {
      proxy.$modal.msgError(`字段键非法：${key}（仅支持字母开头，可含字母 / 数字 / 下划线）`)
      return null
    }
    const dup = fields.value.find((item: DesignerField) => item.uid !== field.uid && item.field === key)
    if (dup) {
      proxy.$modal.msgError(`字段键重复：${key}`)
      return null
    }
    const item: any = {
      field: key,
      label: (field.label || '').trim() || key,
      type: field.type,
      required: !!field.required
    }
    if (field.placeholder) item.placeholder = field.placeholder
    if (hasOptions(field)) {
      item.options = (field.options || [])
        .filter((opt: { label: string; value: string }) => (opt.label || '').trim() || (opt.value || '').trim())
        .map((opt: { label: string; value: string }) => ({
          label: (opt.label || '').trim() || opt.value,
          value: (opt.value || '').trim() || opt.label
        }))
    }
    list.push(item)
  }
  return { fields: list }
}

async function handleSaveDraft() {
  const schema = buildSchema()
  if (!schema) return
  savingDraft.value = true
  try {
    await saveFormSchemaDraft(props.formId, schema)
    proxy.$modal.msgSuccess('草稿已保存')
    emit('saved')
  } catch {
    /* 响应拦截器已提示 */
  } finally {
    savingDraft.value = false
  }
}

async function handleSaveAndPublish() {
  const schema = buildSchema()
  if (!schema) return
  savingPublish.value = true
  try {
    const res = await saveFormSchemaDraft(props.formId, schema)
    const versionId = (res as any)?.data?.id
    if (!versionId) {
      proxy.$modal.msgError('保存草稿未返回版本号，发布失败')
      return
    }
    await publishFormVersion(props.formId, versionId)
    proxy.$modal.msgSuccess('已保存并发布')
    emit('saved')
    close()
  } catch {
    /* 响应拦截器已提示 */
  } finally {
    savingPublish.value = false
  }
}

function close() {
  emit('update:modelValue', false)
}

function handleClosed() {
  fields.value = []
  selectedUid.value = ''
}

watch(
  () => props.modelValue,
  visible => {
    if (visible) loadSchema()
  },
  { immediate: true }
)
</script>

<style scoped lang="scss">
.wf-fd {
  display: flex;
  gap: 12px;
  height: 66vh;
  min-height: 420px;
}

.wf-fd__palette {
  width: 190px;
  flex-shrink: 0;
  border: 1px solid #ebeef5;
  border-radius: 6px;
  padding: 10px;
  overflow-y: auto;
}

.wf-fd__canvas {
  flex: 1;
  min-width: 0;
  border: 1px solid #ebeef5;
  border-radius: 6px;
  padding: 10px;
  display: flex;
  flex-direction: column;
}

.wf-fd__props {
  width: 280px;
  flex-shrink: 0;
  border: 1px solid #ebeef5;
  border-radius: 6px;
  padding: 10px;
  overflow-y: auto;
}

.wf-fd__panel-title {
  margin: 0 0 10px;
  font-size: 14px;
  font-weight: 600;
  color: #303133;
}

.wf-fd__canvas-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 6px;
}

.wf-fd__palette-list {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.wf-fd-palette-item {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 10px;
  border: 1px dashed #dcdfe6;
  border-radius: 6px;
  cursor: grab;
  background: #fafafa;
  transition: border-color 0.2s, background 0.2s;

  &:hover {
    border-color: var(--el-color-primary);
    background: #f0f7ff;
  }
}

.wf-fd-palette-item__icon {
  font-size: 18px;
}

.wf-fd-palette-item__body {
  min-width: 0;
}

.wf-fd-palette-item__label {
  font-size: 13px;
  color: #303133;
  line-height: 1.3;
}

.wf-fd-palette-item__desc {
  font-size: 11px;
  color: #909399;
  line-height: 1.3;
}

.wf-fd__tip {
  margin: 10px 0 0;
  font-size: 11px;
  color: #909399;
  line-height: 1.6;
}

.wf-fd__list {
  flex: 1;
  overflow-y: auto;
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.wf-fd-row {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 10px;
  border: 1px solid #ebeef5;
  border-radius: 6px;
  background: #fff;
  cursor: pointer;
  transition: border-color 0.2s, box-shadow 0.2s;

  &:hover {
    border-color: #c0c4cc;
  }

  &.is-selected {
    border-color: var(--el-color-primary);
    box-shadow: 0 0 0 1px var(--el-color-primary);
  }
}

.wf-fd-row__drag {
  color: #c0c4cc;
  font-size: 15px;
  cursor: grab;
  user-select: none;
}

.wf-fd-row__icon {
  font-size: 17px;
}

.wf-fd-row__main {
  flex: 1;
  min-width: 0;
}

.wf-fd-row__label {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 14px;
  color: #303133;
  line-height: 1.4;
}

.wf-fd-row__req {
  font-size: 10px;
}

.wf-fd-row__field {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 12px;
  color: #909399;

  code {
    background: #f5f7fa;
    border-radius: 3px;
    padding: 1px 5px;
    font-family: Consolas, Monaco, 'Courier New', monospace;
    color: #606266;
  }
}

.wf-fd-row__type {
  color: #c0c4cc;
}

.wf-fd-props__field {
  margin-bottom: 14px;

  label {
    display: block;
    margin-bottom: 6px;
    font-size: 13px;
    color: #606266;
  }
}

.wf-fd-props__opt {
  display: flex;
  align-items: center;
  gap: 4px;
  margin-bottom: 6px;

  .el-input {
    width: 92px;
  }
}

.wf-fd-props__add-opt {
  width: 100%;
}

.wf-fd-props__delete {
  width: 100%;
  margin-top: 4px;
}

:deep(.el-dialog) {
  border-radius: 8px;
}

:deep(.el-dialog__body) {
  padding-top: 12px;
}
</style>
