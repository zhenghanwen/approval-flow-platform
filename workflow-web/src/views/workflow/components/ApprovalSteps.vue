<template>
  <div class="approval-steps">
    <div v-for="(step, index) in steps" :key="step.key || index" class="step">
      <div class="step-axis">
        <span class="dot" :class="step.state"></span>
        <span v-if="index < steps.length - 1" class="line"></span>
      </div>
      <div class="step-body">
        <div class="step-name">{{ step.name }}</div>
        <div v-if="step.approver" class="step-meta">{{ step.approver }}</div>
        <div v-if="step.time" class="step-meta">{{ step.time }}</div>
        <div v-if="step.comment" class="step-comment">{{ step.comment }}</div>
      </div>
    </div>
    <el-empty v-if="!steps.length" description="暂无流程记录" :image-size="60" />
  </div>
</template>

<script setup lang="ts" name="ApprovalSteps">
import type { ApprovalStep } from '@/types'

defineProps<{ steps: ApprovalStep[] }>()
</script>

<style scoped lang="scss">
.approval-steps {
  padding: 4px 0;
}

.step {
  display: flex;
  gap: 12px;
}

.step-axis {
  display: flex;
  flex-direction: column;
  align-items: center;
  width: 12px;
  flex: none;
}

.dot {
  width: 10px;
  height: 10px;
  border-radius: 50%;
  margin-top: 4px;
  background: #dcdfe6;

  &.done {
    background: #67c23a;
  }

  &.active {
    background: #409eff;
  }

  &.wait {
    background: #dcdfe6;
  }
}

.line {
  flex: 1;
  width: 2px;
  margin: 4px 0;
  background: #e4e7ed;
  min-height: 56px;
}

.step-body {
  padding-bottom: 22px;
  flex: 1;
  min-width: 0;
}

.step-name {
  font-size: 14px;
  font-weight: 600;
  color: #303133;
  line-height: 18px;
}

.step-meta {
  margin-top: 6px;
  font-size: 12px;
  color: #909399;
  line-height: 16px;
}

.step-comment {
  margin-top: 6px;
  font-size: 12px;
  color: #67c23a;
  line-height: 16px;
}
</style>
