<template>
  <div class="app-container wf-start">
    <div class="wf-start__header">
      <h2 class="wf-page-title">发起审批</h2>
      <p class="wf-page-desc">选择审批类型，填写表单后提交</p>
    </div>

    <div v-loading="loading" class="wf-start__grid">
      <div
        v-for="item in cards"
        :key="item.code"
        class="wf-type-card"
        role="button"
        tabindex="0"
        @click="startApproval(item)"
        @keyup.enter="startApproval(item)"
      >
        <div class="wf-type-card__head">
          <span class="wf-type-card__icon">{{ item.icon }}</span>
          <span class="wf-type-card__title">{{ item.name }}</span>
        </div>
        <p class="wf-type-card__desc">{{ item.description }}</p>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts" name="WorkflowStart">
import { listApprovalDefinition } from '@/api/workflow/definition'
import type { WfApprovalDefinition } from '@/types'
import { WF_FORM_METAS, getFormMeta } from '../fields'

interface StartCard {
  code: string
  name: string
  description: string
  icon: string
  definitionId: string
}

const router = useRouter()
const { proxy } = getCurrentInstance() as any

const loading = ref(true)
const definitions = ref<WfApprovalDefinition[]>([])

/** 后端有审批类型时按接口数据展示，无数据时回落到设计稿的 6 个入口 */
const cards = computed<StartCard[]>(() => {
  const available = definitions.value.filter(item => item.status !== 'DISABLED')
  if (!available.length) {
    return WF_FORM_METAS.map(meta => ({
      code: meta.code,
      name: meta.name,
      description: meta.description,
      icon: meta.icon,
      definitionId: ''
    }))
  }
  return available.map(item => {
    const code = item.approvalCode || ''
    const meta = getFormMeta(code)
    return {
      code: code || meta.code,
      name: item.approvalName || meta.name,
      description: item.description || meta.description,
      icon: item.icon || meta.icon,
      definitionId: item.id
    }
  })
})

async function getList() {
  loading.value = true
  try {
    const res = await listApprovalDefinition()
    definitions.value = res.data || []
  } catch {
    definitions.value = []
  } finally {
    loading.value = false
  }
}

function startApproval(item: StartCard) {
  if (item.definitionId) {
    router.push({
      path: '/workflow/form',
      query: { code: item.code, definitionId: item.definitionId, name: item.name }
    })
    return
  }
  proxy.$modal.msgWarning('后端暂无已发布的审批类型，表单可预览，提交前需先配置审批类型')
  router.push({ path: '/workflow/form', query: { code: item.code, name: item.name } })
}

getList()
</script>

<style scoped lang="scss">
.wf-start__header {
  padding: 4px 0 20px;
}

.wf-start__grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 16px;
}

.wf-type-card {
  background: #fff;
  border: 1px solid #e8eaed;
  border-radius: 4px;
  padding: 20px 24px 22px;
  min-height: 124px;
  cursor: pointer;
  transition: border-color 0.2s, box-shadow 0.2s, transform 0.2s;
  outline: none;

  &:hover,
  &:focus-visible {
    border-color: var(--el-color-primary);
    box-shadow: 0 4px 14px rgba(64, 158, 255, 0.12);
    transform: translateY(-1px);
  }
}

.wf-type-card__head {
  display: flex;
  align-items: center;
  gap: 12px;
}

.wf-type-card__icon {
  font-size: 20px;
  line-height: 1;
}

.wf-type-card__title {
  font-size: 16px;
  font-weight: 600;
  color: #303133;
}

.wf-type-card__desc {
  margin: 26px 0 0;
  font-size: 13px;
  color: #909399;
  line-height: 20px;
}

@media (max-width: 1200px) {
  .wf-start__grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}

@media (max-width: 768px) {
  .wf-start__grid {
    grid-template-columns: minmax(0, 1fr);
  }
}
</style>
