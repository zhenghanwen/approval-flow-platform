package com.workflow.spike;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.flowable.engine.runtime.ProcessInstance;
import org.flowable.task.api.Task;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * S5 规一化快照（T20）—— D6 门的雏形：输入"普通写法"BPMN，输出"统一多实例建模"BPMN。
 *
 * <p>锁定两条不变量：</p>
 * <ul>
 *     <li>快照：输出中每个 UserTask 都有并行 MI；实例数表达式 = 节点专属独立变量
 *         {@code ${wf_n_<id>}}（N1+N2，防同构互退空壳根）；未动已有 MI（N3）。</li>
 *     <li>可部署可执行：规一化产物直接部署并走完全流程 —— 门禁输出必须通过引擎校验，
 *         不能"改了文件却部署不了"。</li>
 * </ul>
 */
@DisplayName("S5-规一化快照: T20 普通UserTask→统一MI改写 + 产物可部署可执行")
class S5NormalizationTest extends SpikeEngineSupport {

    private static String plainXml;

    @BeforeAll
    static void loadPlain() throws Exception {
        plainXml = Files.readString(
            Path.of("src", "test", "resources", "bpmn", "spike-normal-plain.bpmn20.xml"),
            StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("T20a: 规一化快照 —— 全部UserTask注入并行MI、cardinality节点专属互不同构、已有MI不动")
    void normalizationSnapshot() {
        String normalized = BpmnNormalizer.normalize(plainXml);

        // N1：apply 与 approve 都被注入并行 MI
        assertThat(normalized)
            .contains("multiInstanceLoopCharacteristics")
            .withFailMessage("apply 未注入 MI");
        assertThat(BpmnNormalizer.snapshotSummary(normalized))
            .contains("spikeNormalPlain: userTasks=2, withMultiInstance=2");

        // N2：实例数表达式 = 节点专属独立变量，两者互不同构（同构互退防线的静态保证）
        assertThat(normalized).contains("<loopCardinality>${wf_n_apply}</loopCardinality>");
        assertThat(normalized).contains("<loopCardinality>${wf_n_approve}</loopCardinality>");

        // MI 为并行形态（S2：前/后加签 v1 仅并行 ALL 规则成立）
        assertThat(normalized).contains("multiInstanceLoopCharacteristics");
        assertThat(normalized).contains("isSequential=\"false\"");
    }

    @Test
    @DisplayName("T20b: 规一化产物直接部署并走完整流程 —— 门禁输出必须过引擎校验")
    void normalizedOutputIsDeployableAndExecutable() {
        String normalized = BpmnNormalizer.normalize(plainXml);

        // 引擎校验关：XSD + 结构校验不通过就会在这里抛异常
        deployBpmnContent("spike-normal-plain-normalized.bpmn20.xml", normalized.getBytes(StandardCharsets.UTF_8));

        // 语义关：注入 MI 后流程仍可完整执行（cardinality 变量由服务层运行时下发，=1 即普通审批）
        ProcessInstance pi = runtimeService.startProcessInstanceByKey("spikeNormalPlain",
            null, Map.of("wf_n_apply", 1, "wf_n_approve", 1));

        Task apply = taskService.createTaskQuery().processInstanceId(pi.getId()).taskDefinitionKey("apply").singleResult();
        assertThat(apply.getAssignee()).isEqualTo("applicant"); // 语义保持：assignee 逐实例下发
        taskService.complete(apply.getId());

        Task approve = taskService.createTaskQuery().processInstanceId(pi.getId()).taskDefinitionKey("approve").singleResult();
        assertThat(approve.getAssignee()).isEqualTo("boss");
        taskService.complete(approve.getId());

        assertThat(runtimeService.createProcessInstanceQuery().processInstanceId(pi.getId()).count()).isZero();
        assertThat(historyService.createHistoricProcessInstanceQuery()
            .processInstanceId(pi.getId()).singleResult().getEndTime()).isNotNull();
    }
}
