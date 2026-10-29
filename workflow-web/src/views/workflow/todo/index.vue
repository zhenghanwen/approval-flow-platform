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
            style="width: 200px"
            @keyup.enter="handleQuery"
          />
        </el-form-item>
        <el-form-item label="申请人" prop="applicant">
          <el-input
            v-model="queryParams.applicant"
            placeholder="请输入申请人"
            clearable
            style="width: 200px"
            @keyup.enter="handleQuery"
          />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" icon="Search" @click="handleQuery">搜索</el-button>
          <el-button icon="Refresh" @click="resetQuery">重置</el-button>
        </el-form-item>
      </el-form>
    </div>

    <!-- 待办列表 -->
    <div class="wf-table-card">
      <el-table v-loading="loading" class="wf-table" :data="pagedRows" row-key="id">
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
        <el-table-column label="当前节点" prop="taskName" min-width="140" />
        <el-table-column label="到达时间" min-width="160">
          <template #default="{ row }">{{ formatTime(row.createdAt) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="220" align="left">
          <template #default="{ row }">
            <el-button link type="primary" class="wf-link-btn" @click="openAction(row, 'approve')">同意</el-button>
            <el-button link type="primary" class="wf-link-btn" @click="openAction(row, 'reject')">驳回</el-button>
            <el-button link type="primary" class="wf-link-btn" @click="openAction(row, 'transfer')">转办</el-button>
            <el-button link type="primary" class="wf-link-btn" @click="openAction(row, 'addSign')">加签</el-button>
          </template>
        </el-table-column>
        <template #empty>
          <el-empty description="暂无待办事项" :image-size="90" />
        </template>
      </el-table>
    </div>

    <div v-show="filteredRows.length > 0" class="wf-pagination">
      <pagination
        v-model:page="pageNum"
        v-model:limit="pageSize"
        :total="filteredRows.length"
        :page-sizes="[10, 20, 50]"
        layout="total, sizes, prev, pager, next, jumper"
      />
    </div>

    <action-dialog ref="actionRef" :task-id="currentTaskId" :type="currentType" @success="getList" />
  </div>
</template>

<script setup lang="ts" name="WorkflowTodo">
import ActionDialog from '../components/ActionDialog.vue'
import { listTodoTask } from '@/api/workflow/task'
import { enrichTasks, formatTime, type TaskRow } from '../utils'

const router = useRouter()

const loading = ref(true)
const rows = ref<TaskRow[]>([])
const pageNum = ref(1)
const pageSize = ref(10)
const actionRef = ref()
const currentTaskId = ref('')
const currentType = ref<'approve' | 'reject' | 'transfer' | 'addSign'>('approve')

const data = reactive({
  queryParams: { title: '', applicant: '' }
})
const { queryParams } = toRefs(data)

/** 客户端过滤（接口一次性返回全部待办） */
const filteredRows = computed(() => {
  const title = queryParams.value.title.trim()
  const applicant = queryParams.value.applicant.trim()
  return rows.value.filter(row => {
    const matchTitle = !title || (row.title || '').includes(title)
    const matchApplicant = !applicant || (row.applicant || '').includes(applicant)
    return matchTitle && matchApplicant
  })
})

const pagedRows = computed(() => {
  const start = (pageNum.value - 1) * pageSize.value
  return filteredRows.value.slice(start, start + pageSize.value)
})

async function getList() {
  loading.value = true
  try {
    const res = await listTodoTask()
    rows.value = await enrichTasks(res.data || [])
  } finally {
    loading.value = false
  }
}

function handleQuery() {
  pageNum.value = 1
}

function resetQuery() {
  queryParams.value.title = ''
  queryParams.value.applicant = ''
  pageNum.value = 1
}

function openAction(row: TaskRow, type: 'approve' | 'reject' | 'transfer' | 'addSign') {
  // 后端任务操作接口按 Flowable 任务 id 定位，本地投影 id 仅用于列表行
  currentTaskId.value = row.flowableTaskId || row.id
  currentType.value = type
  actionRef.value?.open()
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
