<template>
  <div class="wf-designer">
    <!-- 顶部工具栏 -->
    <header class="wf-designer__header">
      <div class="wf-designer__title" @click="goBack">
        <el-icon><Back /></el-icon>
        <span>{{ pageTitle }}</span>
      </div>
      <div class="wf-designer__actions">
        <el-button type="primary" icon="Check" :loading="validating" @click="handleValidate">校验</el-button>
        <el-button type="success" icon="Promotion" :loading="publishing" @click="handlePublish">发布</el-button>
        <span class="wf-designer__divider"></span>
        <el-button text :disabled="!canUndo" @click="undo">
          <el-icon><RefreshLeft /></el-icon>
          撤销
        </el-button>
        <el-button text :disabled="!canRedo" @click="redo">
          <el-icon><RefreshRight /></el-icon>
          重做
        </el-button>
        <el-button text @click="fitView">
          <el-icon><FullScreen /></el-icon>
          适应
        </el-button>
        <span class="wf-designer__zoom">
          <el-button text @click="zoomOut">-</el-button>
          <span class="wf-designer__scale">{{ Math.round(scale * 100) }}%</span>
          <el-button text @click="zoomIn">+</el-button>
          <span class="wf-designer__ratio" @click="resetZoom">1:1</span>
        </span>
      </div>
    </header>

    <div class="wf-designer__body">
      <!-- 左侧节点面板 -->
      <aside class="wf-designer__palette">
        <button
          v-for="item in palette"
          :key="item.type"
          type="button"
          class="wf-palette-item"
          :class="{ 'is-active': item.type === 'edge' && connecting }"
          @click="handlePaletteClick(item.type)"
        >
          <span class="wf-palette-item__icon" :class="`is-${item.type}`">{{ item.icon }}</span>
          <span class="wf-palette-item__label">{{ item.label }}</span>
        </button>
      </aside>

      <!-- 中间画布 -->
      <main ref="canvasRef" class="wf-designer__canvas" @click="onCanvasClick">
        <div class="wf-designer__stage" :style="stageStyle">
          <svg class="wf-edges" :width="STAGE_WIDTH" :height="STAGE_HEIGHT">
            <defs>
              <marker
                id="wf-arrow"
                viewBox="0 0 10 10"
                refX="9"
                refY="5"
                markerWidth="7"
                markerHeight="7"
                orient="auto-start-reverse"
              >
                <path d="M 0 0 L 10 5 L 0 10 z" fill="#c0c4cc" />
              </marker>
            </defs>
            <g v-for="edge in edges" :key="edge.id">
              <path :d="edgePath(edge)" fill="none" stroke="#c0c4cc" stroke-width="1.2" marker-end="url(#wf-arrow)" />
              <text
                v-if="edge.label"
                class="wf-edge-label"
                :x="edgeLabelPosition(edge).x"
                :y="edgeLabelPosition(edge).y"
                text-anchor="middle"
              >
                {{ edge.label }}
              </text>
            </g>
          </svg>

          <div
            v-for="node in nodes"
            :key="node.id"
            class="wf-node"
            :class="[`is-${node.type}`, { 'is-selected': node.id === selectedId, 'is-applicant': node.isApplicant }]"
            :style="{ left: `${node.x}px`, top: `${node.y}px` }"
            @mousedown.stop="startDrag($event, node)"
            @click.stop="onNodeClick(node)"
          >
            <template v-if="node.type === 'start'">
              <span class="wf-node__circle"></span>
            </template>
            <template v-else-if="node.type === 'end'">
              <span class="wf-node__end"></span>
            </template>
            <template v-else-if="node.type === 'approval'">
              <span class="wf-node__rect">
                <span class="wf-node__name">{{ node.name }}</span>
                <span v-if="node.isApplicant" class="wf-node__badge is-green">申请</span>
              </span>
            </template>
            <template v-else>
              <span class="wf-node__gateway" :class="`is-${node.type}`">
                <span class="wf-node__gateway-text">{{ node.type === 'parallel' ? '+' : '×' }}</span>
              </span>
            </template>

            <span v-if="node.type !== 'approval'" class="wf-node__caption">{{ node.name }}</span>
            <span
              v-if="node.type === 'approval' && !node.isApplicant"
              class="wf-node__rule"
              :class="ruleClass(node)"
            >
              {{ ruleText(node) }}
            </span>
          </div>
        </div>

        <div v-if="connecting" class="wf-designer__connect-tip">
          {{ connectFrom ? '请点击终点节点完成连线（Esc 取消）' : '请点击起点节点' }}
        </div>
        <div v-else class="wf-designer__hint">点击左侧添加节点，拖动节点调整位置</div>
      </main>

      <!-- 右侧属性面板 -->
      <aside class="wf-designer__props">
        <template v-if="selectedNode">
          <h4 class="wf-props__title">常规</h4>
          <div class="wf-props__field">
            <label>节点 ID</label>
            <el-input
              :key="propsKey"
              :model-value="selectedNode.id"
              size="default"
              @change="(value: string) => renameNode(selectedNode!, value)"
            />
          </div>
          <div class="wf-props__field">
            <label>节点名称</label>
            <el-input v-model="selectedNode.name" />
          </div>

          <template v-if="selectedNode.type === 'approval' && !selectedNode.isApplicant">
            <h4 class="wf-props__title">审批配置</h4>
            <div class="wf-props__field">
              <label>审批人来源</label>
              <el-select v-model="selectedNode.approverType" style="width: 100%">
                <el-option
                  v-for="option in approverOptions"
                  :key="option.value"
                  :label="option.label"
                  :value="option.value"
                />
              </el-select>
            </div>
            <div v-if="needsAssignValue(selectedNode)" class="wf-props__field">
              <label>{{ assignValueLabel(selectedNode) }}</label>
              <el-input
                v-model="selectedNode.assignValue"
                :placeholder="assignValuePlaceholder(selectedNode)"
              />
            </div>
            <div v-if="selectedNode.approverType === 'FORM_FIELD'" class="wf-props__field">
              <label>表单人员字段</label>
              <el-input v-model="selectedNode.formField" placeholder="如：managerId" />
            </div>
            <div class="wf-props__field">
              <label>会签规则</label>
              <el-select v-model="selectedNode.signRule" style="width: 100%">
                <el-option
                  v-for="option in signOptions"
                  :key="option.value"
                  :label="option.label"
                  :value="option.value"
                />
              </el-select>
            </div>
            <div v-if="selectedNode.signRule === 'PERCENT'" class="wf-props__field">
              <label>通过比例（1-100）</label>
              <el-input-number v-model="selectedNode.percent" :min="1" :max="100" style="width: 100%" />
            </div>
            <div class="wf-props__tip">{{ approverTip(selectedNode) }}</div>
          </template>

          <el-button class="wf-props__delete" type="danger" plain size="small" @click="removeSelected">
            删除节点
          </el-button>
        </template>
        <el-empty v-else description="选中节点后可编辑属性" :image-size="70" />
      </aside>
    </div>
  </div>
</template>

<script setup lang="ts" name="WorkflowDesigner">
import { addWorkflow, getWorkflow, listWorkflowVersion, publishWorkflow, validateWorkflow, listWorkflow } from '@/api/workflow/workflow'
import type { WfWorkflowDefinition } from '@/types'

type NodeType = 'start' | 'end' | 'approval' | 'exclusive' | 'parallel'

interface DesignerNode {
  id: string
  name: string
  type: NodeType
  x: number
  y: number
  approverType: string
  assignValue?: string
  formField?: string
  percent?: number
  signRule: string
  isApplicant?: boolean
}

interface DesignerEdge {
  id: string
  from: string
  to: string
  label?: string
}

const STAGE_WIDTH = 1000
const STAGE_HEIGHT = 700

const route = useRoute()
const router = useRouter()
const { proxy } = getCurrentInstance() as any

const loading = ref(false)
const validating = ref(false)
const publishing = ref(false)
const scale = ref(1)
const canvasRef = ref<HTMLElement>()
const propsKey = ref(0)

const workflowId = ref(String(route.query.workflowId || ''))
const definitionId = ref(String(route.query.definitionId || ''))
const workflowName = ref(String(route.query.name || '审批流程'))
const workflowCode = ref('')

const nodes = ref<DesignerNode[]>([])
const edges = ref<DesignerEdge[]>([])
const selectedId = ref('')
const connecting = ref(false)
const connectFrom = ref('')

const history = ref<string[]>([])
const future = ref<string[]>([])

let nodeCounter = 0
let edgeCounter = 0
let dragState: { node: DesignerNode; offsetX: number; offsetY: number } | null = null

const palette = [
  { type: 'start', label: '开始', icon: '○' },
  { type: 'end', label: '结束', icon: '●' },
  { type: 'approval', label: '审批节点', icon: '▭' },
  { type: 'exclusive', label: '排他网关', icon: '◇' },
  { type: 'parallel', label: '并行网关', icon: '＋' },
  { type: 'edge', label: '连线', icon: '→' }
]

const approverOptions = [
  { label: 'DEPT_LEADER（部门负责人）', value: 'DEPT_LEADER' },
  { label: 'DIRECT_MANAGER（直属主管）', value: 'DIRECT_MANAGER' },
  { label: 'USER（指定人员）', value: 'USER' },
  { label: 'ROLE（指定角色）', value: 'ROLE' },
  { label: 'FORM_FIELD（表单人员字段）', value: 'FORM_FIELD' },
  { label: 'EXTERNAL_API（外部接口）', value: 'EXTERNAL_API' }
]

const signOptions = [
  { label: 'ALL（全员通过）', value: 'ALL' },
  { label: 'OR（任意一人）', value: 'OR' },
  { label: 'PERCENT（比例通过）', value: 'PERCENT' }
]

/** 需要额外填写 assignValue 的审批人来源 */
function needsAssignValue(node: DesignerNode) {
  return ['USER', 'ROLE', 'EXTERNAL_API'].includes(node.approverType)
}

function assignValueLabel(node: DesignerNode) {
  if (node.approverType === 'USER') return '用户 ID（逗号分隔）'
  if (node.approverType === 'ROLE') return '角色标识（roleKey）'
  return '接口编码（apiCode）'
}

function assignValuePlaceholder(node: DesignerNode) {
  if (node.approverType === 'USER') return '如：2,3'
  if (node.approverType === 'ROLE') return '如：common'
  return '如：leader_api'
}

function approverTip(node: DesignerNode) {
  if (node.approverType === 'DEPT_LEADER') return '💡 自动取发起人部门负责人，无需配置具体人'
  if (node.approverType === 'DIRECT_MANAGER') return '💡 自动取发起人的直属主管'
  if (node.approverType === 'FORM_FIELD') return '💡 从表单数据中读取人员字段作为审批人'
  if (node.approverType === 'EXTERNAL_API') return '💡 调用已登记的外部接口获取审批人'
  return '💡 提交时按配置解析审批人，解析不到会阻止提交'
}

const pageTitle = computed(() => `${workflowName.value} · 设计`)
const selectedNode = computed(() => nodes.value.find(node => node.id === selectedId.value) || null)
const canUndo = computed(() => history.value.length > 0)
const canRedo = computed(() => future.value.length > 0)
const stageStyle = computed(() => ({
  transform: `scale(${scale.value})`,
  transformOrigin: 'top left'
}))

function snapshot() {
  return JSON.stringify({ nodes: nodes.value, edges: edges.value })
}

function pushHistory() {
  history.value.push(snapshot())
  if (history.value.length > 50) history.value.shift()
  future.value = []
}

function restore(snap: string) {
  const data = JSON.parse(snap)
  nodes.value = data.nodes || []
  edges.value = data.edges || []
  selectedId.value = ''
}

function undo() {
  const previous = history.value.pop()
  if (!previous) return
  future.value.push(snapshot())
  restore(previous)
}

function redo() {
  const next = future.value.pop()
  if (!next) return
  history.value.push(snapshot())
  restore(next)
}

/** 默认画布：与设计稿 P9 一致的采购审批流程 */
function createDefaultGraph() {
  nodes.value = [
    { id: 'start', name: '开始', type: 'start', x: 80, y: 280, approverType: '', signRule: '' },
    {
      id: 'applyNode',
      name: '填写申请',
      type: 'approval',
      x: 210,
      y: 280,
      approverType: '',
      signRule: '',
      isApplicant: true
    },
    { id: 'amountGateway', name: '金额分支', type: 'exclusive', x: 380, y: 280, approverType: '', signRule: '' },
    {
      id: 'deptApproval',
      name: '部门审批',
      type: 'approval',
      x: 540,
      y: 280,
      approverType: 'DEPT_LEADER',
      signRule: 'ALL'
    },
    {
      id: 'financeApproval',
      name: '财务审批',
      type: 'approval',
      x: 720,
      y: 280,
      approverType: 'ROLE',
      assignValue: 'common',
      signRule: 'OR'
    },
    { id: 'end', name: '结束', type: 'end', x: 880, y: 280, approverType: '', signRule: '' },
    {
      id: 'lowAmountApproval',
      name: '备案审批',
      type: 'approval',
      x: 380,
      y: 450,
      approverType: 'DEPT_LEADER',
      signRule: 'ALL'
    }
  ]
  edges.value = [
    { id: 'edge_1', from: 'start', to: 'applyNode' },
    { id: 'edge_2', from: 'applyNode', to: 'amountGateway' },
    { id: 'edge_3', from: 'amountGateway', to: 'deptApproval', label: '金额≥1000' },
    { id: 'edge_4', from: 'deptApproval', to: 'financeApproval' },
    { id: 'edge_5', from: 'financeApproval', to: 'end' },
    { id: 'edge_6', from: 'amountGateway', to: 'lowAmountApproval', label: '金额<1000' },
    { id: 'edge_7', from: 'lowAmountApproval', to: 'end' }
  ]
  markApplicantNode()
}

function markApplicantNode() {
  const startNode = nodes.value.find(node => node.type === 'start')
  nodes.value.forEach(node => {
    node.isApplicant = false
  })
  const applicant = nodes.value.find(
    node =>
      node.type === 'approval' &&
      startNode &&
      edges.value.some(edge => edge.from === startNode.id && edge.to === node.id)
  )
  if (applicant) applicant.isApplicant = true
}

/** 解析后端 configJson */
function applyConfig(configJson?: string): boolean {
  if (!configJson) return false
  try {
    const root = JSON.parse(configJson)
    const assignmentMap =
      root?.nodes && typeof root.nodes === 'object' ? (root.nodes as Record<string, any>) : {}
    const layoutMap =
      root?.layout && typeof root.layout === 'object' ? (root.layout as Record<string, any>) : null

    if (!layoutMap) {
      // 旧格式：只有节点审批人配置，没有画布布局，无法还原连线 → 用默认画布叠加配置
      if (!Object.keys(assignmentMap).length) return false
      createDefaultGraph()
      applyAssignments(assignmentMap)
      markApplicantNode()
      return false
    }

    const parsedNodes: DesignerNode[] = Object.keys(layoutMap).map(key => {
      const cfg = layoutMap[key] || {}
      return {
        id: key,
        name: cfg.name || cfg.nodeName || key,
        type: normalizeType(cfg.type || cfg.nodeType),
        x: Number(cfg.x) || 80,
        y: Number(cfg.y) || 80,
        approverType: cfg.approverType || '',
        signRule: cfg.signRule || 'ALL',
        isApplicant: !!cfg.isApplicant
      }
    })
    if (!parsedNodes.length) return false
    const parsedEdges: DesignerEdge[] = Array.isArray(root.edges)
      ? root.edges
          .filter((edge: any) => edge?.from && edge?.to)
          .map((edge: any, index: number) => ({
            id: edge.id || `edge_${index + 1}`,
            from: edge.from,
            to: edge.to,
            label: edge.label || ''
          }))
      : []
    nodes.value = parsedNodes
    edges.value = parsedEdges
    applyAssignments(assignmentMap)
    markApplicantNode()
    return true
  } catch {
    return false
  }
}

/** 把 nodes 里的审批人配置合并到画布节点 */
function applyAssignments(assignmentMap: Record<string, any>) {
  nodes.value.forEach(node => {
    const cfg = assignmentMap[node.id]
    if (!cfg) return
    if (cfg.assignType || cfg.approverType) {
      node.approverType = cfg.assignType || cfg.approverType
    }
    node.assignValue = cfg.assignValue == null ? '' : String(cfg.assignValue)
    node.formField = cfg.formField == null ? '' : String(cfg.formField)
    node.percent = cfg.percent == null ? node.percent : Number(cfg.percent)
    node.signRule = cfg.completionRule || cfg.signRule || node.signRule
  })
}

function normalizeType(type?: string): NodeType {
  const value = String(type || '').toLowerCase()
  if (value.includes('start')) return 'start'
  if (value.includes('end')) return 'end'
  if (value.includes('parallel')) return 'parallel'
  if (value.includes('gateway') || value.includes('exclusive')) return 'exclusive'
  return 'approval'
}

async function loadGraph() {
  loading.value = true
  try {
    // 没有 workflowId 时按审批类型查找已有流程
    if (!workflowId.value && definitionId.value) {
      try {
        const res = await listWorkflow({ approvalDefinitionId: definitionId.value } as Partial<WfWorkflowDefinition>)
        const found = (res.data || []).find(item => item.approvalDefinitionId === definitionId.value)
        if (found) {
          workflowId.value = found.id
          workflowName.value = found.workflowName || workflowName.value
          workflowCode.value = found.workflowCode || ''
        }
      } catch {
        /* 忽略 */
      }
    }

    if (workflowId.value) {
      try {
        const detail = await getWorkflow(workflowId.value)
        if (detail.data) {
          workflowName.value = detail.data.workflowName || workflowName.value
          workflowCode.value = detail.data.workflowCode || workflowCode.value
          definitionId.value = detail.data.approvalDefinitionId || definitionId.value
        }
      } catch {
        /* 忽略 */
      }
      try {
        const versionRes = await listWorkflowVersion(workflowId.value)
        const versions = (versionRes.data || [])
          .filter(item => item.status === 'PUBLISHED')
          .sort((a, b) => (b.versionNo || 0) - (a.versionNo || 0))
        if (versions[0]?.configJson) {
          applyConfig(versions[0].configJson)
        }
      } catch {
        /* 忽略 */
      }
    }

    if (!nodes.value.length) createDefaultGraph()
  } finally {
    loading.value = false
  }
}

function handlePaletteClick(type: string) {
  if (type === 'edge') {
    connecting.value = true
    connectFrom.value = ''
    proxy.$modal.msg('请依次点击起点和终点节点')
    return
  }
  addNode(type as NodeType)
}

function addNode(type: NodeType) {
  pushHistory()
  nodeCounter += 1
  const idBase = type === 'approval' ? 'approvalNode' : type === 'exclusive' ? 'gateway' : type
  let id = `${idBase}_${nodeCounter}`
  while (nodes.value.some(node => node.id === id)) {
    nodeCounter += 1
    id = `${idBase}_${nodeCounter}`
  }
  const nameMap: Record<NodeType, string> = {
    start: '开始',
    end: '结束',
    approval: '审批节点',
    exclusive: '排他网关',
    parallel: '并行网关'
  }
  const node: DesignerNode = {
    id,
    name: nameMap[type],
    type,
    x: 500,
    y: 350,
    approverType: type === 'approval' ? 'DEPT_LEADER' : '',
    signRule: type === 'approval' ? 'ALL' : ''
  }
  nodes.value.push(node)

  const source = selectedNode.value
  if (source && source.id !== node.id && type !== 'start') {
    edgeCounter += 1
    edges.value.push({ id: `edge_${edgeCounter}`, from: source.id, to: node.id })
  }
  markApplicantNode()
  selectedId.value = node.id
}

function onNodeClick(node: DesignerNode) {
  if (connecting.value) {
    if (!connectFrom.value) {
      connectFrom.value = node.id
      return
    }
    if (connectFrom.value !== node.id) {
      pushHistory()
      edgeCounter += 1
      edges.value.push({ id: `edge_${edgeCounter}`, from: connectFrom.value, to: node.id })
    }
    connecting.value = false
    connectFrom.value = ''
    return
  }
  selectedId.value = node.id
}

function onCanvasClick() {
  if (connecting.value) {
    connecting.value = false
    connectFrom.value = ''
    return
  }
  selectedId.value = ''
}

function renameNode(node: DesignerNode, value: string) {
  const nextId = String(value || '').trim()
  if (!/^[A-Za-z0-9_-]{1,100}$/.test(nextId)) {
    proxy.$modal.msgWarning('节点 ID 仅支持字母、数字、下划线和短横线')
    propsKey.value += 1
    return
  }
  if (nextId === node.id) return
  if (nodes.value.some(item => item.id === nextId)) {
    proxy.$modal.msgWarning('节点 ID 已存在')
    propsKey.value += 1
    return
  }
  pushHistory()
  const oldId = node.id
  node.id = nextId
  edges.value.forEach(edge => {
    if (edge.from === oldId) edge.from = nextId
    if (edge.to === oldId) edge.to = nextId
  })
  selectedId.value = nextId
  propsKey.value += 1
}

function removeSelected() {
  const node = selectedNode.value
  if (!node) return
  if (node.type === 'start' || node.type === 'end') {
    proxy.$modal.msgWarning('开始/结束节点不可删除')
    return
  }
  pushHistory()
  nodes.value = nodes.value.filter(item => item.id !== node.id)
  edges.value = edges.value.filter(edge => edge.from !== node.id && edge.to !== node.id)
  selectedId.value = ''
  markApplicantNode()
}

function startDrag(event: MouseEvent, node: DesignerNode) {
  selectedId.value = node.id
  const stage = (event.currentTarget as HTMLElement).closest('.wf-designer__stage') as HTMLElement
  if (!stage) return
  const rect = stage.getBoundingClientRect()
  dragState = {
    node,
    offsetX: (event.clientX - rect.left) / scale.value - node.x,
    offsetY: (event.clientY - rect.top) / scale.value - node.y
  }
  pushHistory()
  window.addEventListener('mousemove', onDrag)
  window.addEventListener('mouseup', stopDrag)
}

function onDrag(event: MouseEvent) {
  if (!dragState || !canvasRef.value) return
  const stage = canvasRef.value.querySelector('.wf-designer__stage') as HTMLElement
  if (!stage) return
  const rect = stage.getBoundingClientRect()
  const x = (event.clientX - rect.left) / scale.value - dragState.offsetX
  const y = (event.clientY - rect.top) / scale.value - dragState.offsetY
  dragState.node.x = Math.min(Math.max(30, x), STAGE_WIDTH - 30)
  dragState.node.y = Math.min(Math.max(30, y), STAGE_HEIGHT - 30)
}

function stopDrag() {
  dragState = null
  window.removeEventListener('mousemove', onDrag)
  window.removeEventListener('mouseup', stopDrag)
}

function nodeById(id: string) {
  return nodes.value.find(node => node.id === id)
}

function nodeSize(node: DesignerNode) {
  if (node.type === 'approval') return { w: 132, h: 44 }
  if (node.type === 'start') return { w: 40, h: 40 }
  if (node.type === 'end') return { w: 36, h: 36 }
  return { w: 48, h: 48 }
}

function edgePath(edge: DesignerEdge) {
  const source = nodeById(edge.from)
  const target = nodeById(edge.to)
  if (!source || !target) return ''
  const dx = target.x - source.x
  const dy = target.y - source.y
  const length = Math.hypot(dx, dy) || 1
  const sourceOffset = Math.min(nodeSize(source).w, nodeSize(source).h) / 2
  const targetOffset = Math.min(nodeSize(target).w, nodeSize(target).h) / 2 + 6
  const x1 = source.x + (dx / length) * sourceOffset
  const y1 = source.y + (dy / length) * sourceOffset
  const x2 = target.x - (dx / length) * targetOffset
  const y2 = target.y - (dy / length) * targetOffset
  return `M ${x1} ${y1} L ${x2} ${y2}`
}

function edgeLabelPosition(edge: DesignerEdge) {
  const source = nodeById(edge.from)
  const target = nodeById(edge.to)
  if (!source || !target) return { x: 0, y: 0 }
  return {
    x: (source.x + target.x) / 2,
    y: (source.y + target.y) / 2 - 8
  }
}

function ruleText(node: DesignerNode) {
  if (node.signRule === 'OR') return '或签 OR'
  if (node.signRule === 'PERCENT') return '比例通过'
  return '会签 ALL'
}

function ruleClass(node: DesignerNode) {
  if (node.signRule === 'OR') return 'is-or'
  if (node.signRule === 'PERCENT') return 'is-percent'
  return 'is-all'
}

function zoomIn() {
  scale.value = Math.min(2, Number((scale.value + 0.1).toFixed(2)))
}

function zoomOut() {
  scale.value = Math.max(0.5, Number((scale.value - 0.1).toFixed(2)))
}

function resetZoom() {
  scale.value = 1
}

function fitView() {
  scale.value = 1
}

function goBack() {
  router.back()
}

/** 生成 BPMN XML（后端校验 + 规一化） */
function buildBpmnXml() {
  const processId = (workflowCode.value || 'workflow_flow').replace(/[^A-Za-z0-9_-]/g, '_')
  const lines: string[] = []
  lines.push('<?xml version="1.0" encoding="UTF-8"?>')
  lines.push(
    '<definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xmlns:flowable="http://flowable.org/bpmn" targetNamespace="http://ruoyi.com/workflow">'
  )
  lines.push(`  <process id="${processId}" name="${xmlEscape(workflowName.value)}" isExecutable="true">`)

  nodes.value.forEach(node => {
    const id = xmlEscape(node.id)
    const name = xmlEscape(node.name)
    if (node.type === 'start') {
      lines.push(`    <startEvent id="${id}" name="${name}" />`)
    } else if (node.type === 'end') {
      lines.push(`    <endEvent id="${id}" name="${name}" />`)
    } else if (node.type === 'approval') {
      lines.push(`    <userTask id="${id}" name="${name}" />`)
    } else if (node.type === 'parallel') {
      lines.push(`    <parallelGateway id="${id}" name="${name}" />`)
    } else {
      lines.push(`    <exclusiveGateway id="${id}" name="${name}" />`)
    }
  })

  edges.value.forEach((edge, index) => {
    const source = nodeById(edge.from)
    const target = nodeById(edge.to)
    if (!source || !target) return
    const condition = source.type === 'exclusive' ? conditionExpression(edge.label) : ''
    lines.push(
      `    <sequenceFlow id="flow_${index + 1}" sourceRef="${xmlEscape(source.id)}" targetRef="${xmlEscape(target.id)}">`
    )
    if (condition) {
      lines.push(
        `      <conditionExpression xsi:type="tFormalExpression"><![CDATA[${condition}]]></conditionExpression>`
      )
    }
    lines.push('    </sequenceFlow>')
  })

  lines.push('  </process>')
  lines.push('</definitions>')
  return lines.join('\n')
}

function conditionExpression(label?: string) {
  if (!label) return ''
  const amount = label.match(/(\d+(?:\.\d+)?)/)
  if (!amount) return ''
  const number = amount[1]
  if (label.includes('<') || label.includes('＜')) return `\${amount < ${number}}`
  if (label.includes('>') || label.includes('＞') || label.includes('≥')) {
    return `\${amount >= ${number}}`
  }
  return ''
}

function xmlEscape(value: string) {
  return String(value ?? '')
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&apos;')
}

function buildConfigJson() {
  // nodes：后端审批人解析契约（{ assignType, assignValue, formField, completionRule, percent }）
  const nodeConfig: Record<string, any> = {}
  // layout：画布信息（后端忽略，设计器回显用）
  const layout: Record<string, any> = {}

  nodes.value.forEach(node => {
    layout[node.id] = {
      name: node.name,
      type: node.type,
      x: Math.round(node.x),
      y: Math.round(node.y),
      isApplicant: !!node.isApplicant
    }
    // 申请节点由后端默认指派给申请人并自动完成，不写审批人配置
    if (node.type !== 'approval' || node.isApplicant) return
    const config: Record<string, any> = {
      assignType: node.approverType || 'DEPT_LEADER',
      completionRule: node.signRule || 'ALL'
    }
    if (['USER', 'ROLE', 'EXTERNAL_API'].includes(config.assignType)) {
      config.assignValue = (node.assignValue || '').trim()
    }
    if (config.assignType === 'FORM_FIELD') {
      config.formField = (node.formField || '').trim()
    }
    if (config.completionRule === 'PERCENT') {
      config.percent = node.percent || 100
    }
    nodeConfig[node.id] = config
  })

  return JSON.stringify({
    nodes: nodeConfig,
    layout,
    edges: edges.value.map(edge => ({
      id: edge.id,
      from: edge.from,
      to: edge.to,
      label: edge.label || ''
    }))
  })
}

async function ensureWorkflow(): Promise<boolean> {
  if (workflowId.value) return true
  if (!definitionId.value) {
    proxy.$modal.msgWarning('缺少流程定义，请从「审批类型配置」进入设计器')
    return false
  }
  const code = (workflowCode.value || `wf_${definitionId.value.slice(0, 8)}_flow`).replace(/[^A-Za-z0-9_-]/g, '_')
  try {
    const res = await addWorkflow({
      approvalDefinitionId: definitionId.value,
      workflowCode: code,
      workflowName: workflowName.value
    })
    if (res.data?.id) {
      workflowId.value = res.data.id
      workflowCode.value = res.data.workflowCode || code
      return true
    }
  } catch {
    /* 响应拦截器已提示 */
  }
  return false
}

async function handleValidate() {
  if (!(await ensureWorkflow())) return
  validating.value = true
  try {
    await validateWorkflow(workflowId.value, { bpmnXml: buildBpmnXml() })
    proxy.$modal.msgSuccess('校验通过')
  } finally {
    validating.value = false
  }
}

async function handlePublish() {
  if (!(await ensureWorkflow())) return
  publishing.value = true
  try {
    const res = await publishWorkflow(workflowId.value, {
      bpmnXml: buildBpmnXml(),
      configJson: buildConfigJson(),
      workflowCode: workflowCode.value,
      workflowName: workflowName.value
    })
    const version: any = (res as any)?.data
    proxy.$modal.msgSuccess(version?.versionNo ? `发布成功，版本 v${version.versionNo}` : '发布成功')
  } finally {
    publishing.value = false
  }
}

function handleKeydown(event: KeyboardEvent) {
  if (event.key === 'Escape') {
    connecting.value = false
    connectFrom.value = ''
  }
}

onMounted(() => {
  loadGraph()
  window.addEventListener('keydown', handleKeydown)
})

onUnmounted(() => {
  window.removeEventListener('keydown', handleKeydown)
  window.removeEventListener('mousemove', onDrag)
  window.removeEventListener('mouseup', stopDrag)
})
</script>

<style scoped lang="scss">
.wf-designer {
  display: flex;
  flex-direction: column;
  height: calc(100vh - 84px);
  min-height: 560px;
  background: #f5f6f8;
}

.wf-designer__header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  flex: none;
  height: 56px;
  padding: 0 20px;
  background: #fff;
  border-bottom: 1px solid #e4e7ed;
}

.wf-designer__title {
  display: flex;
  align-items: center;
  gap: 10px;
  font-size: 15px;
  font-weight: 600;
  color: #303133;
  cursor: pointer;
}

.wf-designer__actions {
  display: flex;
  align-items: center;
  gap: 8px;
}

.wf-designer__divider {
  width: 1px;
  height: 20px;
  margin: 0 6px;
  background: #e4e7ed;
}

.wf-designer__zoom {
  display: flex;
  align-items: center;
  gap: 2px;
  margin-left: 8px;
}

.wf-designer__scale {
  min-width: 40px;
  text-align: center;
  font-size: 13px;
  color: #606266;
}

.wf-designer__ratio {
  margin-left: 6px;
  padding-left: 10px;
  border-left: 1px solid #e4e7ed;
  font-size: 13px;
  color: #909399;
  cursor: pointer;
}

.wf-designer__body {
  display: flex;
  flex: 1;
  min-height: 0;
}

.wf-designer__palette {
  flex: none;
  width: 80px;
  padding: 12px 8px;
  background: #fff;
  border-right: 1px solid #e4e7ed;
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.wf-palette-item {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 6px;
  width: 100%;
  padding: 10px 4px;
  border: 1px solid transparent;
  border-radius: 4px;
  background: transparent;
  cursor: pointer;
  color: #606266;

  &:hover {
    background: #f5f7fa;
  }

  &.is-active {
    border-color: var(--el-color-primary);
    background: #ecf5ff;
  }
}

.wf-palette-item__icon {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 22px;
  height: 22px;
  font-size: 18px;
  line-height: 1;

  &.is-start {
    color: #909399;
  }

  &.is-end {
    color: #303133;
  }

  &.is-approval {
    color: #409eff;
  }

  &.is-exclusive {
    color: #e6a23c;
  }

  &.is-parallel {
    color: #409eff;
  }

  &.is-edge {
    color: #909399;
  }
}

.wf-palette-item__label {
  font-size: 12px;
  line-height: 16px;
}

.wf-designer__canvas {
  position: relative;
  flex: 1;
  min-width: 0;
  overflow: auto;
  background-color: #f7f8fa;
  background-image: radial-gradient(#e4e7ed 1px, transparent 1px);
  background-size: 16px 16px;
}

.wf-designer__stage {
  position: relative;
  width: 1000px;
  height: 700px;
  margin: 24px;
}

.wf-edges {
  position: absolute;
  inset: 0;
  pointer-events: none;
}

.wf-edge-label {
  font-size: 12px;
  fill: #e6a23c;
}

.wf-node {
  position: absolute;
  display: flex;
  flex-direction: column;
  align-items: center;
  transform: translate(-50%, -50%);
  cursor: move;
  user-select: none;

  &.is-selected .wf-node__rect,
  &.is-selected .wf-node__gateway,
  &.is-selected .wf-node__circle,
  &.is-selected .wf-node__end {
    box-shadow: 0 0 0 3px rgba(64, 158, 255, 0.28);
  }
}

.wf-node__circle {
  display: block;
  width: 40px;
  height: 40px;
  border: 2px solid #909399;
  border-radius: 50%;
  background: #fff;
}

.wf-node__end {
  display: block;
  width: 36px;
  height: 36px;
  border-radius: 50%;
  background: #303133;
}

.wf-node__rect {
  position: relative;
  display: flex;
  align-items: center;
  justify-content: center;
  width: 132px;
  height: 44px;
  padding: 0 14px;
  border: 2px solid #409eff;
  border-radius: 4px;
  background: #fff;
  box-sizing: border-box;
}

.wf-node.is-approval.is-applicant .wf-node__rect {
  border-color: #67c23a;
}

.wf-node__name {
  font-size: 14px;
  color: #303133;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.wf-node__badge {
  position: absolute;
  top: -10px;
  right: -8px;
  padding: 1px 7px;
  border-radius: 3px;
  font-size: 11px;
  line-height: 16px;
  color: #fff;

  &.is-green {
    background: #67c23a;
  }
}

.wf-node__gateway {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 48px;
  height: 48px;
  border: 2px solid #e6a23c;
  background: #fff;
  transform: rotate(45deg);
  font-size: 18px;
  color: #e6a23c;

  &.is-parallel {
    border-color: #409eff;
    color: #409eff;
  }
}

.wf-node__gateway-text {
  display: block;
  transform: rotate(-45deg);
}

.wf-node__caption {
  margin-top: 8px;
  font-size: 12px;
  color: #909399;
  white-space: nowrap;
}

.wf-node__rule {
  position: absolute;
  top: 48px;
  left: 50%;
  transform: translateX(-50%);
  padding: 1px 7px;
  border-radius: 3px;
  font-size: 11px;
  line-height: 16px;
  white-space: nowrap;

  &.is-all {
    background: #409eff;
    color: #fff;
  }

  &.is-or {
    background: #e6a23c;
    color: #fff;
  }

  &.is-percent {
    background: #909399;
    color: #fff;
  }
}

.wf-designer__hint,
.wf-designer__connect-tip {
  position: sticky;
  left: 16px;
  bottom: 12px;
  display: inline-block;
  margin: 0 16px 12px;
  padding: 4px 12px;
  border-radius: 3px;
  background: rgba(255, 255, 255, 0.9);
  font-size: 12px;
  color: #909399;
}

.wf-designer__connect-tip {
  color: #409eff;
}

.wf-designer__props {
  flex: none;
  width: 300px;
  padding: 20px;
  background: #fff;
  border-left: 1px solid #e4e7ed;
  overflow: auto;
}

.wf-props__title {
  margin: 0 0 14px;
  padding-bottom: 8px;
  border-bottom: 1px solid #f0f2f5;
  font-size: 14px;
  font-weight: 600;
  color: #303133;

  &:not(:first-child) {
    margin-top: 24px;
  }
}

.wf-props__field {
  margin-bottom: 14px;

  label {
    display: block;
    margin-bottom: 6px;
    font-size: 12px;
    color: #909399;
  }
}

.wf-props__tip {
  margin-top: 16px;
  padding: 10px 12px;
  border-radius: 4px;
  background: #ecf5ff;
  font-size: 12px;
  line-height: 18px;
  color: #409eff;
}

.wf-props__delete {
  margin-top: 24px;
  width: 100%;
}
</style>
