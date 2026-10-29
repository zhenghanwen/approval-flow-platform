<template>
  <div class="app-container">
    <div class="wf-table-card" v-loading="loading">
      <el-table class="wf-table" :data="rows" row-key="id">
        <el-table-column label="序号" type="index" width="70" align="center" />
        <el-table-column label="审批标题" min-width="240">
          <template #default="{ row }">
            <el-button link type="primary" class="wf-title-link" @click="goDetail(row)">
              <span v-if="row.readFlag === false" class="wf-unread-dot"></span>
              {{ row.title || '—' }}
            </el-button>
          </template>
        </el-table-column>
        <el-table-column label="发起人" prop="applicant" min-width="100" />
        <el-table-column label="当前节点" prop="currentNodeName" min-width="140" />
        <el-table-column label="抄送时间" min-width="160">
          <template #default="{ row }">{{ formatTime(row.ccTime) }}</template>
        </el-table-column>
        <el-table-column label="状态" min-width="100">
          <template #default="{ row }">{{ statusText(row.businessStatus) }}</template>
        </el-table-column>
        <template #empty>
          <div class="wf-empty">
            <el-empty description="暂无抄送数据" :image-size="90" />
            <p class="wf-empty__tip">提交审批时可选择抄送人，被抄送人将在此处看到该审批单。</p>
          </div>
        </template>
      </el-table>
    </div>
  </div>
</template>

<script setup lang="ts" name="WorkflowCc">
import { listCcApproval, markCcRead } from '@/api/workflow/cc'
import type { WfApprovalCcVo } from '@/types'
import { formatTime, statusText } from '../utils'

const router = useRouter()
const loading = ref(true)
const rows = ref<WfApprovalCcVo[]>([])

async function getList() {
  loading.value = true
  try {
    const res = await listCcApproval()
    rows.value = res.data || []
  } catch {
    rows.value = []
  } finally {
    loading.value = false
  }
}

async function goDetail(row: WfApprovalCcVo) {
  if (!row.approvalInstanceId) return
  // 打开详情前标记已读（失败不影响跳转）
  if (row.readFlag === false && row.id) {
    markCcRead(row.id).catch(() => {})
  }
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

.wf-unread-dot {
  display: inline-block;
  width: 8px;
  height: 8px;
  margin-right: 6px;
  border-radius: 50%;
  background-color: var(--el-color-danger);
  vertical-align: middle;
}

.wf-empty {
  padding: 24px 0 32px;
}

.wf-empty__tip {
  margin: 0;
  font-size: 12px;
  color: #909399;
}
</style>
