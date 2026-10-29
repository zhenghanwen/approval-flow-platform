<template>
  <div class="app-container">
    <div class="wf-panel">
      <el-form ref="queryRef" :inline="true" :model="queryParams" @submit.prevent>
        <el-form-item label="审批标题" prop="title">
          <el-input
            v-model="queryParams.title"
            placeholder="请输入审批标题"
            clearable
            style="width: 200px"
            @keyup.enter="handleQuery"
          />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" icon="Search" @click="handleQuery">搜索</el-button>
        </el-form-item>
      </el-form>
    </div>

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
        <el-table-column label="类型" prop="typeName" min-width="120" />
        <el-table-column label="申请人" prop="applicant" min-width="100" />
        <el-table-column label="办理时间" min-width="160">
          <template #default="{ row }">{{ formatTime(row.completedAt) }}</template>
        </el-table-column>
        <el-table-column label="结果" prop="result" min-width="100" />
        <template #empty>
          <el-empty description="暂无已办事项" :image-size="90" />
        </template>
      </el-table>
    </div>
  </div>
</template>

<script setup lang="ts" name="WorkflowDone">
import { listDoneTask } from '@/api/workflow/task'
import { enrichTasks, formatTime, type TaskRow } from '../utils'

const router = useRouter()

const loading = ref(true)
const rows = ref<TaskRow[]>([])

const data = reactive({ queryParams: { title: '' } })
const { queryParams } = toRefs(data)

const filteredRows = computed(() => {
  const title = queryParams.value.title.trim()
  return rows.value.filter(row => !title || (row.title || '').includes(title))
})

async function getList() {
  loading.value = true
  try {
    const res = await listDoneTask()
    rows.value = await enrichTasks(res.data || [], true)
  } finally {
    loading.value = false
  }
}

function handleQuery() {
  /* 客户端过滤，无需请求接口 */
}

function goDetail(row: TaskRow) {
  router.push(`/workflow/detail/${row.approvalInstanceId}`)
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
</style>
