<template>
  <div class="app-container wf-wizard">
    <!-- 向导头部：标题 + 步骤条 -->
    <div class="wf-wizard__head">
      <div class="wf-wizard__head-row">
        <h2 class="wf-wizard__title">新建审批模板</h2>
        <el-button text @click="handleCancel">取消</el-button>
      </div>
      <el-steps :active="activeStep" align-center finish-status="success" process-status="process" class="wf-wizard__steps">
        <el-step title="基本信息" description="编码与名称" />
        <el-step title="表单设计" description="建立审批表单" />
        <el-step title="流程设计" description="保存 / 存草稿 / 直接发布" />
      </el-steps>
    </div>

    <div class="wf-wizard__body">
      <!-- 第 1 步：基本信息 -->
      <div v-show="activeStep === 0" class="wf-wizard__panel">
        <el-form ref="basicRef" :model="basic" :rules="basicRules" label-width="100px" class="wf-wizard__form">
          <el-form-item label="模板编码" prop="approvalCode">
            <el-input v-model="basic.approvalCode" placeholder="如：PURCHASE" maxlength="50" :disabled="!!templateId" />
          </el-form-item>
          <el-form-item label="模板名称" prop="approvalName">
            <el-input v-model="basic.approvalName" placeholder="如：采购审批" maxlength="50" />
          </el-form-item>
          <el-form-item label="描述" prop="description">
            <el-input v-model="basic.description" type="textarea" :rows="3" placeholder="选填" maxlength="200" show-word-limit />
          </el-form-item>
          <el-form-item label="允许撤回">
            <el-switch v-model="basic.allowWithdraw" />
          </el-form-item>
          <el-form-item label="允许取消">
            <el-switch v-model="basic.allowCancel" />
          </el-form-item>
        </el-form>
      </div>

      <!-- 第 2 步：表单设计 -->
      <div v-show="activeStep === 1" class="wf-wizard__panel">
        <template v-if="!formId">
          <el-form ref="formCreateRef" :model="formMeta" :rules="formCreateRules" label-width="100px" class="wf-wizard__form">
            <el-form-item label="表单编码" prop="formCode">
              <el-input v-model="formMeta.formCode" placeholder="如：PURCHASE_FORM" maxlength="50" />
            </el-form-item>
            <el-form-item label="表单名称" prop="formName">
              <el-input v-model="formMeta.formName" placeholder="如：采购审批表单" maxlength="50" />
            </el-form-item>
          </el-form>
        </template>
        <template v-else>
          <div class="wf-wizard__form-card">
            <div class="wf-wizard__form-card-row">
              <span class="wf-wizard__form-label">表单</span>
              <span class="wf-wizard__form-name">{{ formMeta.formName }}</span>
              <el-tag type="info" size="small">{{ formMeta.formCode }}</el-tag>
            </div>
            <div class="wf-wizard__form-card-row">
              <span class="wf-wizard__form-label">绑定版本</span>
              <span v-if="currentFormVersionId" class="wf-wizard__form-bound">
                <el-tag type="success" size="small">已发布 v{{ currentFormVersionNo }}</el-tag>
              </span>
              <el-tag v-else type="warning" size="small">未发布（请先保存并发布表单）</el-tag>
            </div>
            <el-button type="primary" plain @click="openFormDesigner">重新设计</el-button>
          </div>
        </template>
      </div>

      <!-- 第 3 步：流程设计 -->
      <div v-show="activeStep === 2" class="wf-wizard__panel wf-wizard__panel--designer">
        <WorkflowDesigner
          v-if="templateId"
          :definition-id="templateId"
          :name="basic.approvalName || basic.approvalCode"
          embedded
          @published="handleFlowPublished"
          @saved="handleFlowSaved"
        />
      </div>
    </div>

    <!-- 底部导航 -->
    <div class="wf-wizard__footer">
      <el-button v-if="activeStep > 0" @click="activeStep -= 1">上一步</el-button>
      <div class="wf-wizard__footer-right">
        <el-button v-if="activeStep < 2" type="primary" :loading="saving" @click="handleNext">
          下一步
        </el-button>
      </div>
    </div>

    <!-- 表单可视化设计器（弹窗，复用表单管理） -->
    <FormDesigner
      v-model="formDesignerVisible"
      :form-id="formId || ''"
      :form-name="formMeta.formName || '审批表单'"
      @saved="refreshFormBinding"
    />
  </div>
</template>

<script setup lang="ts" name="WorkflowTemplateWizard">
import FormDesigner from '../components/FormDesigner.vue'
import WorkflowDesigner from '../designer/index.vue'
import { addApprovalDefinition, publishApprovalDefinition, updateApprovalDefinition } from '@/api/workflow/definition'
import { createForm, listFormVersion } from '@/api/workflow/form'

const router = useRouter()
const { proxy } = getCurrentInstance() as any

const activeStep = ref(0)
const saving = ref(false)
const publishing = ref(false)

/** 第 1 步：模板基本信息 */
const templateId = ref('')
const basicRef = ref()
const basic = reactive({
  approvalCode: '',
  approvalName: '',
  description: '',
  allowWithdraw: true,
  allowCancel: true
})
const basicRules = {
  approvalCode: [{ required: true, message: '请输入模板编码', trigger: 'blur' }],
  approvalName: [{ required: true, message: '请输入模板名称', trigger: 'blur' }]
}

/** 第 2 步：表单 */
const formId = ref('')
const formMeta = reactive({ formCode: '', formName: '' })
const formCreateRef = ref()
const formCreateRules = {
  formCode: [{ required: true, message: '请输入表单编码', trigger: 'blur' }],
  formName: [{ required: true, message: '请输入表单名称', trigger: 'blur' }]
}
const currentFormVersionId = ref('')
const currentFormVersionNo = ref<number>()
const formDesignerVisible = ref(false)

/** 第 1 步 → 下一步：创建模板（approval-definitions，实体即审批模板）；已创建则回改基本信息 */
async function handleNext() {
  if (activeStep.value === 0) {
    const valid = await basicRef.value?.validate().catch(() => false)
    if (!valid) return
    saving.value = true
    try {
      if (templateId.value) {
        // 从后续步骤返回修改基本信息
        await updateApprovalDefinition(templateId.value, {
          approvalName: basic.approvalName,
          description: basic.description,
          allowWithdraw: basic.allowWithdraw,
          allowCancel: basic.allowCancel
        })
      } else {
        const res = await addApprovalDefinition({
          approvalCode: basic.approvalCode.trim().toUpperCase(),
          approvalName: basic.approvalName,
          description: basic.description,
          allowWithdraw: basic.allowWithdraw,
          allowCancel: basic.allowCancel
        })
        const createdId: string = (res as any)?.data?.id || ''
        if (!createdId) {
          proxy.$modal.msgError('创建模板失败，未返回模板ID')
          return
        }
        templateId.value = createdId
        // 表单编码/名称默认带出，可改
        if (!formMeta.formCode) formMeta.formCode = `${basic.approvalCode}_FORM`
        if (!formMeta.formName) formMeta.formName = `${basic.approvalName}表单`
      }
      activeStep.value = 1
    } catch {
      /* 响应拦截器已提示 */
    } finally {
      saving.value = false
    }
    return
  }

  if (activeStep.value === 1) {
    // 未创建表单 → 先建表单
    if (!formId.value) {
      await handleCreateForm()
      if (!formId.value) return
    }
    // 表单必须已发布才能进入流程步骤（模板发布门禁要求 PUBLISHED 版本）
    if (!currentFormVersionId.value) {
      proxy.$modal.msgWarning('表单尚未发布，请先在表单设计中「保存并发布」')
      return
    }
    activeStep.value = 2
  }
}

/** 第 2 步：新建表单并打开设计器 */
async function handleCreateForm() {
  const valid = await formCreateRef.value?.validate().catch(() => false)
  if (!valid) return
  saving.value = true
  try {
    const res = await createForm({
      formCode: formMeta.formCode.trim().toUpperCase(),
      formName: formMeta.formName
    })
    const createdId: string = (res as any)?.data?.id || ''
    if (!createdId) {
      proxy.$modal.msgError('创建表单失败，未返回表单ID')
      return
    }
    formId.value = createdId
    openFormDesigner()
  } catch {
    /* 响应拦截器已提示 */
  } finally {
    saving.value = false
  }
}

function openFormDesigner() {
  if (!formId.value) return
  formDesignerVisible.value = true
}

/** 表单保存/发布后刷新绑定：取最新已发布版本 */
async function refreshFormBinding() {
  if (!formId.value) return
  try {
    const res = await listFormVersion(formId.value)
    const published = (res.data || [])
      .filter(item => item.status === 'PUBLISHED')
      .sort((a, b) => (b.versionNo || 0) - (a.versionNo || 0))[0]
    currentFormVersionId.value = published?.id || ''
    currentFormVersionNo.value = published?.versionNo
  } catch {
    /* 忽略 */
  }
}

/** 第 3 步：流程存草稿（模板保持 DRAFT，可从列表继续编辑） */
function handleFlowSaved() {
  proxy.$modal.msgSuccess('流程草稿已保存，模板仍为草稿状态，可在「审批模板」列表中继续编辑')
}

/** 第 3 步：流程直接发布 → 绑定表单/流程版本 + 发布模板，完成整个向导 */
async function handleFlowPublished(payload: { workflowId: string; versionId: string; versionNo: number }) {
  if (!templateId.value) return
  if (!currentFormVersionId.value) {
    proxy.$modal.msgWarning('表单尚未发布，无法完成模板发布。请先返回「表单设计」步骤保存并发布表单')
    return
  }
  publishing.value = true
  try {
    await updateApprovalDefinition(templateId.value, {
      currentFormVersionId: currentFormVersionId.value,
      currentFlowVersionId: payload.versionId
    })
    await publishApprovalDefinition(templateId.value)
    proxy.$modal.msgSuccess(`审批模板已发布（流程 v${payload.versionNo}）`)
    router.push('/workflow-type')
  } catch {
    /* 响应拦截器已提示 */
  } finally {
    publishing.value = false
  }
}

function handleCancel() {
  router.push('/workflow-type')
}
</script>

<style scoped lang="scss">
.wf-wizard {
  max-width: 1200px;
  margin: 0 auto;
  display: flex;
  flex-direction: column;
  min-height: calc(100vh - 100px);
}

.wf-wizard__head {
  padding: 12px 0 4px;

  .wf-wizard__head-row {
    display: flex;
    align-items: center;
    justify-content: space-between;
    margin-bottom: 8px;
  }
}

.wf-wizard__title {
  margin: 0;
  font-size: 18px;
  font-weight: 600;
  color: #303133;
}

.wf-wizard__steps {
  padding: 8px 40px 0;
}

.wf-wizard__body {
  flex: 1;
  margin-top: 20px;
}

.wf-wizard__panel {
  background: #fff;
  border: 1px solid #ebeef5;
  border-radius: 8px;
  padding: 28px 32px;

  &.wf-wizard__panel--designer {
    padding: 0;
    overflow: hidden;
  }
}

.wf-wizard__form {
  max-width: 640px;
  margin: 0 auto;
}

.wf-wizard__form-card {
  max-width: 640px;
  margin: 0 auto;
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.wf-wizard__form-card-row {
  display: flex;
  align-items: center;
  gap: 12px;
}

.wf-wizard__form-label {
  width: 76px;
  flex-shrink: 0;
  font-size: 13px;
  color: #909399;
  text-align: right;
}

.wf-wizard__form-name {
  font-size: 14px;
  color: #303133;
  font-weight: 600;
}

.wf-wizard__footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-top: 20px;
  padding-top: 16px;
  border-top: 1px solid #f0f2f5;
}
</style>
