package com.ruoyi.workflow.bpmn;

import java.io.StringReader;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.Map;
import java.util.Set;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamReader;

import org.flowable.bpmn.converter.BpmnXMLConverter;
import org.flowable.bpmn.model.BpmnModel;
import org.flowable.bpmn.model.FlowElement;
import org.flowable.bpmn.model.FlowNode;
import org.flowable.bpmn.model.MultiInstanceLoopCharacteristics;
import org.flowable.bpmn.model.Process;
import org.flowable.bpmn.model.SequenceFlow;
import org.flowable.bpmn.model.StartEvent;
import org.flowable.bpmn.model.UserTask;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ruoyi.common.exception.ServiceException;

/**
 * BPMN 校验规一化门禁（M3 核心，D1/D6 的落实点；M0 结论平移）。
 *
 * <p>职责链：</p>
 * <ol>
 *     <li><b>校验</b>：节点类型白名单、结构合法性（起止事件、无孤立节点、连接完备）、嵌套深度限制；</li>
 *     <li><b>规一化</b>（M0 BpmnNormalizer 结论升级）：所有未带 MI 的 UserTask 改写为
 *         collection 驱动的并行多实例（N1+N2，见 {@link #ensureMultiInstance}）；
 *         自带 MI 的节点不动（N3，校验 collection 名节点专属）；</li>
 *     <li>输出再次通过 BpmnXMLConverter 序列化，保证可被引擎部署校验。</li>
 * </ol>
 *
 * <p>白名单（v1）：startEvent / endEvent / userTask / sequenceFlow / exclusiveGateway / parallelGateway。</p>
 */
@Service
public class BpmnValidationService {

    /** 审批人 collection 命名约定（节点专属，M0 N2 同构规避的落实：天然互不同构）。 */
    public static final String USERS_COLLECTION_PREFIX = "wf_users_";

    /** 节点类型白名单（v1，匹配 Flowable model 的 getSimpleName()）。 */
    private static final Set<String> ALLOWED_TYPES = Set.of(
        "StartEvent", "EndEvent", "UserTask", "SequenceFlow",
        "ExclusiveGateway", "ParallelGateway");

    /** 最大嵌套深度（网关串联层级，防恶意深链）。 */
    private static final int MAX_DEPTH = 10;

    /** 会签/或签/比例 completionRule 取值。 */
    public static final String RULE_ALL = "ALL";
    public static final String RULE_OR = "OR";
    public static final String RULE_PERCENT = "PERCENT";

    private final ObjectMapper objectMapper;

    public BpmnValidationService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 门禁入口：校验 + 规一化 + 序列化（无节点配置，completionRule 默认 ALL）。
     *
     * @param bpmnXml 前端提交的 BPMN XML
     * @return 规一化后的 BPMN XML（UTF-8）
     */
    public String validateAndNormalize(String bpmnXml) {
        return validateAndNormalize(bpmnXml, null);
    }

    /**
     * 门禁入口：校验 + 规一化 + 序列化。
     *
     * @param bpmnXml    前端提交的 BPMN XML
     * @param configJson 节点配置（含 completionRule：ALL/OR/PERCENT），可为空
     * @return 规一化后的 BPMN XML（UTF-8）
     */
    public String validateAndNormalize(String bpmnXml, String configJson) {
        if (bpmnXml == null || bpmnXml.isBlank()) {
            throw new ServiceException("BPMN XML 不能为空");
        }
        BpmnModel model = parse(bpmnXml);
        validate(model);
        normalize(model, parseNodeConfigs(configJson));
        return serialize(model);
    }

    /**
     * 解析 configJson 的节点配置：{ "nodes": { "<nodeId>": { ... } } }。
     */
    @SuppressWarnings("unchecked")
    private Map<String, Map<String, Object>> parseNodeConfigs(String configJson) {
        Map<String, Map<String, Object>> nodeConfigs = new java.util.HashMap<>();
        if (configJson == null || configJson.isBlank()) {
            return nodeConfigs;
        }
        try {
            Object root = objectMapper.readValue(configJson, Object.class);
            if (root instanceof Map<?, ?> rootMap && rootMap.get("nodes") instanceof Map<?, ?> nodes) {
                for (Map.Entry<?, ?> entry : nodes.entrySet()) {
                    if (entry.getKey() instanceof String nodeId
                        && entry.getValue() instanceof Map<?, ?> cfg) {
                        Map<String, Object> normalized = new java.util.HashMap<>();
                        for (Map.Entry<?, ?> e : cfg.entrySet()) {
                            if (e.getKey() instanceof String k) {
                                normalized.put(k, e.getValue());
                            }
                        }
                        nodeConfigs.put(nodeId, normalized);
                    }
                }
            }
        } catch (Exception e) {
            throw new ServiceException("节点配置 JSON 解析失败: " + e.getMessage());
        }
        return nodeConfigs;
    }

    /**
     * 解析 BPMN XML 为模型。XML 非法直接拒绝。
     */
    private BpmnModel parse(String bpmnXml) {
        try {
            XMLInputFactory inputFactory = XMLInputFactory.newFactory();
            inputFactory.setProperty(XMLInputFactory.IS_COALESCING, true);
            XMLStreamReader reader = inputFactory.createXMLStreamReader(new StringReader(bpmnXml));
            return new BpmnXMLConverter().convertToBpmnModel(reader);
        } catch (Exception e) {
            throw new ServiceException("BPMN XML 解析失败: " + e.getMessage());
        }
    }

    /**
     * 结构校验：类型白名单、起止事件、无孤立节点、连接完备、嵌套深度。
     */
    private void validate(BpmnModel model) {
        if (model.getProcesses().isEmpty()) {
            throw new ServiceException("BPMN 无 process 定义");
        }
        for (Process process : model.getProcesses()) {
            validateProcess(process);
        }
    }

    private void validateProcess(Process process) {
        Collection<FlowElement> elements = process.getFlowElements();
        if (elements.isEmpty()) {
            throw new ServiceException("流程 [" + process.getId() + "] 无任何节点");
        }
        boolean hasStart = elements.stream().anyMatch(e -> e instanceof StartEvent);
        boolean hasEnd = elements.stream().anyMatch(e -> e instanceof org.flowable.bpmn.model.EndEvent);
        if (!hasStart) {
            throw new ServiceException("流程 [" + process.getId() + "] 缺少开始事件");
        }
        if (!hasEnd) {
            throw new ServiceException("流程 [" + process.getId() + "] 缺少结束事件");
        }

        // 类型白名单 + 节点 ID 合法性
        for (FlowElement element : elements) {
            if (!ALLOWED_TYPES.contains(element.getClass().getSimpleName())) {
                throw new ServiceException("不支持的节点类型: " + element.getClass().getSimpleName());
            }
            if (element.getId() == null || element.getId().isBlank()
                || !element.getId().matches("[A-Za-z0-9_\\-]{1,100}")) {
                throw new ServiceException("节点 ID 非法: " + element.getId());
            }
        }

        // 连接完备性：每个 FlowNode 必须有出边（结束事件除外）与入边（开始事件除外）
        for (FlowElement element : elements) {
            if (!(element instanceof FlowNode)) {
                continue;
            }
            FlowNode node = (FlowNode) element;
            if (!(element instanceof StartEvent) && node.getIncomingFlows().isEmpty()) {
                throw new ServiceException("节点 [" + element.getId() + "] 无入边");
            }
            if (!(element instanceof org.flowable.bpmn.model.EndEvent) && node.getOutgoingFlows().isEmpty()) {
                throw new ServiceException("节点 [" + element.getId() + "] 无出边");
            }
        }

        // 嵌套深度限制：沿网关出边递归计数
        for (FlowElement element : elements) {
            if (element instanceof StartEvent) {
                int depth = countGatewayDepth((FlowNode) element, 0, new java.util.HashSet<>());
                if (depth > MAX_DEPTH) {
                    throw new ServiceException("流程嵌套深度超过限制: " + depth);
                }
            }
        }
    }

    private int countGatewayDepth(FlowNode node, int depth, Set<String> visited) {
        if (depth > MAX_DEPTH) {
            return depth;
        }
        if (!(node instanceof org.flowable.bpmn.model.Gateway)) {
            return depth;
        }
        if (!visited.add(node.getId())) {
            return depth; // 环路保护
        }
        int maxChild = depth;
        for (SequenceFlow flow : node.getOutgoingFlows()) {
            if (flow.getTargetFlowElement() instanceof FlowNode target) {
                maxChild = Math.max(maxChild, countGatewayDepth(target, depth + 1, visited));
            }
        }
        return maxChild;
    }

    /**
     * 规一化（M0 BpmnNormalizer 结论落实：N1 审批节点统一多实例 + N2 节点专属变量 + N3 自带 MI 不动）。
     *
     * <p>产品形态采用 <b>collection 驱动</b>（Flowable 标准多实例审批模式，S6CollectionProbeTest 实证）：
     * 审批节点注入 {@code flowable:collection="wf_users_<id>"} +
     * {@code flowable:elementVariable="wf_assignee_<id>"} + {@code assignee=${wf_assignee_<id>}}，
     * 实例数由审批人集合大小决定。collection 名节点专属（N2 的 issue #3944 同构规避：两个节点
     * collection 表达式相同，互退会造空壳 MI 根卡死——专属命名天然互不同构）。</p>
     *
     * <p><b>例外：申请节点</b>（起始事件直达的 UserTask）保持普通任务，办理人=${applicantUserId}——
     * change-state 重新进入 collection-MI 节点会造空壳执行树（S6 探针结论），退回申请人依赖普通任务形态
     * （M0 S1 T06 实证）。</p>
     *
     * <p><b>会签/或签/比例（M4）</b>：节点配置 completionRule 决定 completionCondition 变体——
     * ALL（默认，引擎缺省即全员）/ OR（任意一人）/ PERCENT（比例达标），见 {@link #applyCompletionRule}。</p>
     */
    private void normalize(BpmnModel model, Map<String, Map<String, Object>> nodeConfigs) {
        for (Process process : model.getProcesses()) {
            // 起始事件直达的 UserTask = 申请节点（保持普通任务）
            java.util.Set<String> applicantNodeIds = new java.util.HashSet<>();
            for (FlowElement fe : process.getFlowElements()) {
                if (fe instanceof StartEvent start) {
                    for (SequenceFlow flow : start.getOutgoingFlows()) {
                        if (flow.getTargetFlowElement() instanceof UserTask ut) {
                            applicantNodeIds.add(ut.getId());
                        }
                    }
                }
            }
            for (FlowElement fe : process.getFlowElements()) {
                if (fe instanceof UserTask ut) {
                    if (applicantNodeIds.contains(ut.getId())) {
                        ut.setAssignee("${applicantUserId}");
                    } else {
                        ensureMultiInstance(ut, nodeConfigs.get(ut.getId()));
                    }
                }
            }
        }
    }

    private void ensureMultiInstance(UserTask task, Map<String, Object> nodeConfig) {
        if (task.getLoopCharacteristics() != null) {
            // N3：自带 MI 不改写，但校验 collection 名须节点专属（拒绝 legacy loopCardinality 形态）
            String ref = task.getLoopCharacteristics().getInputDataItem();
            if (ref == null || !ref.startsWith(USERS_COLLECTION_PREFIX)) {
                throw new ServiceException("多实例 collection 须节点专属（前缀 " + USERS_COLLECTION_PREFIX + "）: "
                    + task.getId());
            }
            return;
        }
        String elementVariable = "wf_assignee_" + task.getId();
        MultiInstanceLoopCharacteristics mi = new MultiInstanceLoopCharacteristics();
        mi.setSequential(false); // 并行 MI（M0：前/后加签 v1 仅并行 ALL 规则成立）
        // Flowable 6.8 序列化映射（S6CollectionProbeTest 实证）：
        //   setInputDataItem(集合变量名) → flowable:collection="wf_users_<id>"
        //   setElementVariable(元素变量)  → flowable:elementVariable="wf_assignee_<id>"
        mi.setInputDataItem(USERS_COLLECTION_PREFIX + task.getId()); // collection 节点专属（N2 同构规避）
        mi.setElementVariable(elementVariable);
        applyCompletionRule(task, mi, nodeConfig);
        task.setLoopCharacteristics(mi);
        task.setAssignee("${" + elementVariable + "}");
    }

    /**
     * completionRule → completionCondition（M4 会签/或签/比例）。
     *
     * <p>引擎缺省 completionCondition 即全员 ALL，故 ALL 不写入；OR 与 PERCENT 显式写入。</p>
     */
    private void applyCompletionRule(UserTask task, MultiInstanceLoopCharacteristics mi,
                                     Map<String, Object> nodeConfig) {
        String rule = null;
        Integer percent = null;
        if (nodeConfig != null) {
            Object ruleObj = nodeConfig.get("completionRule");
            if (ruleObj != null) {
                rule = String.valueOf(ruleObj).toUpperCase();
            }
            Object percentObj = nodeConfig.get("percent");
            if (percentObj instanceof Number n) {
                percent = n.intValue();
            } else if (percentObj instanceof String s && !s.isBlank()) {
                try {
                    percent = Integer.parseInt(s.trim());
                } catch (NumberFormatException ignore) {
                    // 下方统一校验
                }
            }
        }
        if (rule == null || rule.isBlank() || RULE_ALL.equals(rule)) {
            return; // 缺省即 ALL，不写 completionCondition
        }
        switch (rule) {
            case RULE_OR -> mi.setCompletionCondition("${nrOfCompletedInstances >= 1}");
            case RULE_PERCENT -> {
                if (percent == null || percent < 1 || percent > 100) {
                    throw new ServiceException("节点 [" + task.getId()
                        + "] PERCENT 规则必须配置 1-100 的 percent");
                }
                // 整数化：completed*100 >= instances*percent
                mi.setCompletionCondition("${nrOfCompletedInstances * 100 >= nrOfInstances * " + percent + "}");
            }
            default -> throw new ServiceException("节点 [" + task.getId()
                + "] completionRule 仅支持 ALL/OR/PERCENT: " + rule);
        }
    }

    private String serialize(BpmnModel model) {
        try {
            byte[] out = new BpmnXMLConverter().convertToXML(model);
            return new String(out, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new ServiceException("BPMN 序列化失败: " + e.getMessage());
        }
    }

    /**
     * 快照诊断辅助：统计某 XML 中 UserTask 总数与已注入 MI 的数量（供测试/日志用）。
     */
    public String snapshotSummary(String normalizedXml) {
        try {
            BpmnModel model = parse(normalizedXml);
            StringWriter sw = new StringWriter();
            for (Process process : model.getProcesses()) {
                long tasks = 0;
                long withMi = 0;
                for (FlowElement fe : process.getFlowElements()) {
                    if (fe instanceof UserTask ut) {
                        tasks++;
                        if (ut.getLoopCharacteristics() != null) {
                            withMi++;
                        }
                    }
                }
                sw.append(process.getId()).append(": userTasks=").append(String.valueOf(tasks))
                    .append(", withMultiInstance=").append(String.valueOf(withMi)).append("; ");
            }
            return sw.toString().trim();
        } catch (Exception e) {
            throw new ServiceException("快照诊断失败: " + e.getMessage());
        }
    }
}
