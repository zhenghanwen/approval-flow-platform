<template>
  <el-dialog v-model="visible" :title="title" width="520px" append-to-body @closed="reset">
    <el-form ref="formRef" :model="form" :rules="rules" label-width="84px">
      <el-form-item v-if="type === 'transfer'" label="转交给" prop="toUserId">
        <el-select
          v-model="form.toUserId"
          filterable
          allow-create
          default-first-option
          clearable
          placeholder="选择用户（可输入用户ID）"
          style="width: 100%"
        >
          <el-option v-for="u in users" :key="u.userId" :label="u.nickName || u.userName" :value="String(u.userId)" />
        </el-select>
      </el-form-item>
      <el-form-item v-if="type === 'addSign'" label="加签方式" prop="signType">
        <el-radio-group v-model="form.signType">
          <el-radio value="FIRST">前加签</el-radio>
          <el-radio value="AFTER">后加签</el-radio>
        </el-radio-group>
      </el-form-item>
      <el-form-item v-if="type === 'addSign'" label="加签人" prop="addUsers">
        <el-select
          v-model="form.addUsers"
          multiple
          filterable
          allow-create
          default-first-option
          placeholder="选择或输入用户ID，可多选"
          style="width: 100%"
        >
          <el-option v-for="u in users" :key="u.userId" :label="u.nickName || u.userName" :value="String(u.userId)" />
        </el-select>
      </el-form-item>
      <el-form-item :label="commentLabel" :prop="type === 'approve' ? '' : 'comment'">
        <el-input
          v-model="form.comment"
          type="textarea"
          :rows="3"
          :placeholder="commentPlaceholder"
          maxlength="200"
          show-word-limit
        />
      </el-form-item>
    </el-form>
    <template #footer>
      <div class="dialog-footer">
        <el-button type="primary" :loading="submitting" @click="submit">确 定</el-button>
        <el-button @click="visible = false">取 消</el-button>
      </div>
    </template>
  </el-dialog>
</template>

<script setup lang="ts" name="WorkflowActionDialog">
import { approveTask, rejectTask, transferTask, addSignTask } from '@/api/workflow/task'
import { listUser } from '@/api/system/user'
import type { SysUser } from '@/types'

type ActionType = 'approve' | 'reject' | 'transfer' | 'addSign'

const props = defineProps<{
  taskId: string
  type: ActionType
}>()

const emit = defineEmits<{ (e: 'success'): void }>()

const { proxy } = getCurrentInstance() as any
const visible = ref(false)
const submitting = ref(false)
const users = ref<SysUser[]>([])
const formRef = ref()

const titleMap: Record<ActionType, string> = {
  approve: '同意审批',
  reject: '驳回审批',
  transfer: '转办审批',
  addSign: '加签审批'
}
const title = computed(() => titleMap[props.type])
const commentLabel = computed(() => (props.type === 'reject' ? '驳回意见' : '审批意见'))
const commentPlaceholder = computed(() =>
  props.type === 'approve' ? '填写审批意见（可选）...' : '请填写意见，最多 200 字'
)

const form = reactive({
  comment: '',
  toUserId: '',
  signType: 'AFTER',
  addUsers: [] as string[]
})

const rules = computed(() => ({
  toUserId: props.type === 'transfer' ? [{ required: true, message: '请选择转交人', trigger: 'change' }] : [],
  addUsers: props.type === 'addSign' ? [{ required: true, message: '请选择加签人', trigger: 'change' }] : [],
  comment:
    props.type === 'reject'
      ? [{ required: true, message: '驳回意见不能为空', trigger: 'blur' }]
      : []
}))

function open() {
  visible.value = true
  if (props.type === 'transfer' || props.type === 'addSign') loadUsers()
}

async function loadUsers() {
  try {
    const res = await listUser({ pageNum: 1, pageSize: 200 })
    users.value = res.rows || []
  } catch {
    users.value = []
  }
}

function reset() {
  form.comment = ''
  form.toUserId = ''
  form.signType = 'AFTER'
  form.addUsers = []
  submitting.value = false
  proxy.resetForm('formRef')
}

async function submit() {
  await formRef.value?.validate(async (valid: boolean) => {
    if (!valid) return
    submitting.value = true
    try {
      if (props.type === 'approve') {
        await approveTask(props.taskId, form.comment)
      } else if (props.type === 'reject') {
        await rejectTask(props.taskId, form.comment)
      } else if (props.type === 'transfer') {
        await transferTask(props.taskId, form.toUserId, form.comment)
      } else {
        await addSignTask(props.taskId, {
          signType: form.signType,
          addUsers: form.addUsers,
          reason: form.comment
        })
      }
      proxy.$modal.msgSuccess('操作成功')
      visible.value = false
      emit('success')
    } finally {
      submitting.value = false
    }
  })
}

defineExpose({ open })
</script>
