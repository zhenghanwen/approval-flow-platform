<template>
  <div class="app-container">
    <!-- 查询条件 -->
    <div class="wf-panel">
      <el-form ref="queryRef" :inline="true" :model="queryParams" @submit.prevent>
        <el-form-item label="审批标题" prop="title">
          <el-input
            v-model="queryParams.title"
            placeholder="请输入审批标题"
            clearable
            style="width: 220px"
            @keyup.enter="handleQuery"
          />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" icon="Search" @click="handleQuery">搜索</el-button>
        </el-form-item>
      </el-form>
    </div>

    <!-- 我发起的 -->
    <div class="wf-table-card">
      <el-table v-loading="loading" class="wf-table" :data="filteredRows" row-key="id">
        <el-table-column label="序号" type="index" width="70" align="center" />
        <el-table-column label="审批标题" min-width="240">
          <template #default="{ row }">
            <el-button link type="primary" class="wf-title-link" @click="goDetail(row)">
              {{ row.title || '—' }}
            </el-button>
          </template>
        </el-table-column>
        <el-table-column label="类型" min-width="120">
          <template #default="{ row }">{{ typeName(row) }}</template>
        </el-table-column>
        <el-table-column label="当前节点" min-width="140">
          <template #default="{ row }">
            {{ row.businessStatus === 'APPROVED' ? '已完成' : row.currentNodeName || '—' }}
          </template>
        </el-table-column>
        <el-table-column label="状态" min-width="100">
          <template #default="{ row }">
            <span :class="['wf-status', `wf-status--${row.businessStatus}`]">{{ statusText(row.businessStatus) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="提交时间" min-width="160">
          <template #default="{ row }">{{ formatTime(row.submittedAt) }}</template>
        </el-table-column>
        <template #empty>
          <el-empty description="暂无发起的审批" :image-size="90" />
        </template>
      </el-table>
    </div>
  </div>
</template>

<script setup lang="ts" name="WorkflowMine">
import { listMyApproval } from '@/api/workflow/approval'
import type { WfApprovalDefinition, WfApprovalInstance } from '@/types'
import { formatTime, loadDefinitionMap, statusText } from '../utils'

const router = useRouter()

const loading = ref(true)
const rows = ref<WfApprovalInstance[]>([])
const definitions = ref<Map<string, WfApprovalDefinition>>(new Map())

const data = reactive({ queryParams: { title: '' } })
const { queryParams } = toRefs(data)

const filteredRows = computed(() => {
  const keyword = queryParams.value.title.trim()
  return rows.value.filter(row => !keyword || (row.title || '').includes(keyword))
})

function typeName(row: WfApprovalInstance) {
  if (!row.approvalDefinitionId) return '—'
  return definitions.value.get(row.approvalDefinitionId)?.approvalName || '—'
}

async function getList() {
  loading.value = true
  try {
    const res = await listMyApproval()
    rows.value = res.data || []
    definitions.value = await loadDefinitionMap()
  } catch {
    rows.value = []
  } finally {
    loading.value = false
  }
}

function handleQuery() {
  /* 客户端过滤，无需请求接口 */
}

function goDetail(row: WfApprovalInstance) {
  router.push(`/workflow/detail/${row.id}`)
}

getList()
</script>

<style scoped lang="scss">
.wf-title-link.el-button.is-link {
  font-size: 14px;
  color: #303133;
}

.wf-title-link.el-button.is-link:hover {
  color: var(--el-color-primary);
}

.wf-status {
  color: #606266;

  &--PENDING {
    color: #409eff;
  }

  &--APPROVED {
    color: #67c23a;
  }

  &--RETURNED {
    color: #f56c6c;
  }
}
</style>
