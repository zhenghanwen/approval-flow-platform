package com.workflow.spike;

import java.io.StringReader;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamReader;

import org.flowable.bpmn.converter.BpmnXMLConverter;
import org.flowable.bpmn.model.BpmnModel;
import org.flowable.bpmn.model.MultiInstanceLoopCharacteristics;
import org.flowable.bpmn.model.Process;
import org.flowable.bpmn.model.UserTask;

/**
 * BPMN 规一化器雏形（D1 统一建模 + D6 门禁的地基，M0 产出；M2 平移到 bpmn-server 校验链）。
 *
 * <p>规则（每条都有 S1/S2 集成测试背书，见《可行性分析》3.2/3.5 节）：</p>
 * <ul>
 *     <li><b>N1 审批节点统一多实例建模</b>：所有未带 loopCharacteristics 的 UserTask 注入并行多实例
 *         —— 申请节点也统一注入（实例数=1 退化为普通任务），保证全部审批/待办节点同构，
 *         前/后加签与退回走同一 API（S2 实测：引擎层同一 changeState/moveActivityIdTo）。</li>
 *     <li><b>N2 实例数走节点专属独立变量</b>：cardinality 形如 {@code ${wf_n_<activityId>}}
 *         —— 同构表达式（两节点同字符串）互退时引擎会造出空壳 MI 根卡死（flowable issue #3944，
 *         7.1.0 未修，S1 已立哨兵测试 spike-return-sameconfig）；独立变量天然互不同构。</li>
 *     <li><b>N3 collection 驱动形态保留</b>：自带 MI 的节点不动（如 S2 的
 *         loopDataInputRef+inputDataItem+ALL completionCondition 形态），
 *         collection 名须节点专属（wf_users_&lt;id&gt;），该约束由校验门禁执行、不在本雏形自动改写。</li>
 * </ul>
 *
 * <p>实现采用官方 {@link BpmnXMLConverter} 模型级改写（非字符串 hack），
 * 输出必须再次可通过 XSD 与引擎部署校验 —— 该不变量由 S5 快照测试锁定（T20）。</p>
 */
public class BpmnNormalizer {

    /** 注入实例数变量前缀（节点专属独立变量命名约定）。 */
    public static final String CARDINALITY_VAR_PREFIX = "wf_n_";

    /** 会签候选人 collection 命名约定（节点专属）。 */
    public static final String USERS_COLLECTION_PREFIX = "wf_users_";

    /**
     * 规一化：输入 BPMN XML 字符串，输出注入统一多实例后的 XML 字符串。
     *
     * @return 规一化后的 BPMN XML（UTF-8）
     */
    public static String normalize(String bpmnXml) {
        try {
            XMLInputFactory inputFactory = XMLInputFactory.newFactory();
            inputFactory.setProperty(XMLInputFactory.IS_COALESCING, true);
            XMLStreamReader reader = inputFactory.createXMLStreamReader(new StringReader(bpmnXml));
            BpmnModel model = new BpmnXMLConverter().convertToBpmnModel(reader);

            for (Process process : model.getProcesses()) {
                process.getFlowElements().stream()
                    .filter(fe -> fe instanceof UserTask)
                    .map(fe -> (UserTask) fe)
                    .forEach(task -> ensureMultiInstance(task));
            }

            byte[] out = new BpmnXMLConverter().convertToXML(model);
            return new String(out, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("BPMN 规一化失败： " + e.getMessage(), e);
        }
    }

    /**
     * N1+N2：为缺多实例的 UserTask 注入并行 MI，实例数走节点专属独立变量。
     * （顺带 v1 取舍说明：不自动生成 completionCondition，默认全部完成=ALL，与 S2 ALL 规则一致。）
     */
    private static void ensureMultiInstance(UserTask task) {
        if (task.getLoopCharacteristics() != null) {
            return; // N3：已有 MI 不改写
        }
        MultiInstanceLoopCharacteristics mi = new MultiInstanceLoopCharacteristics();
        mi.setSequential(false); // 并行 MI（S2：前/后加签 v1 仅并行 ALL 规则成立）
        mi.setLoopCardinality("${" + CARDINALITY_VAR_PREFIX + task.getId() + "}"); // N2：节点专属
        task.setLoopCharacteristics(mi);
    }

    /**
     * 快照诊断辅助：统计某 XML 中 UserTask 总数与已注入 MI 的数量（供测试/门禁日志用）。
     */
    public static String snapshotSummary(String normalizedXml) {
        try {
            XMLInputFactory inputFactory = XMLInputFactory.newFactory();
            inputFactory.setProperty(XMLInputFactory.IS_COALESCING, true);
            XMLStreamReader reader = inputFactory.createXMLStreamReader(new StringReader(normalizedXml));
            BpmnModel model = new BpmnXMLConverter().convertToBpmnModel(reader);
            StringWriter sw = new StringWriter();
            for (Process process : model.getProcesses()) {
                long tasks = 0;
                long withMi = 0;
                for (org.flowable.bpmn.model.FlowElement fe : process.getFlowElements()) {
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
            throw new IllegalStateException("快照诊断失败： " + e.getMessage(), e);
        }
    }
}
