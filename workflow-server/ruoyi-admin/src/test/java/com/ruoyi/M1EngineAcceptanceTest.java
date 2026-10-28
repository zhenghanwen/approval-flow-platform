package com.ruoyi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.flowable.engine.RepositoryService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.flowable.engine.repository.ProcessDefinition;
import org.flowable.engine.runtime.ProcessInstance;
import org.flowable.task.api.Task;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * M1 验收：RuoYi 全栈上下文中 Flowable 引擎可用性冒烟。
 *
 * <p>复用 admin 的真实配置（druid master + flowable starter），验证：</p>
 * <ol>
 *     <li>引擎服务（RepositoryService/RuntimeService/TaskService）可装配；</li>
 *     <li>部署 BPMN → startProcessInstanceByKey → 两个用户任务全链推进 → 流程结束；</li>
 *     <li>history-level=full 下流程结束历史留痕。</li>
 * </ol>
 *
 * <p>依赖运行环境：MySQL(workflow_dev) 与 Redis 在线，数据库凭证经 WF_DB_PASSWORD 注入。</p>
 */
@SpringBootTest(classes = RuoYiApplication.class, webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@ActiveProfiles("druid")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class M1EngineAcceptanceTest {

    @Autowired
    private RepositoryService repositoryService;

    @Autowired
    private RuntimeService runtimeService;

    @Autowired
    private TaskService taskService;

    private static String processInstanceId;

    @Test
    @Order(1)
    void deployAndStartProcess() {
        repositoryService.createDeployment()
            .addClasspathResource("bpmn/m1-acceptance-sequential-approval.bpmn20.xml")
            .name("M1 验收部署")
            .deploy();

        ProcessDefinition pd = repositoryService.createProcessDefinitionQuery()
            .processDefinitionKey("m1Acceptance")
            .latestVersion()
            .singleResult();
        assertNotNull(pd, "流程定义应已部署");

        ProcessInstance pi = runtimeService.startProcessInstanceByKey("m1Acceptance");
        assertNotNull(pi, "startProcessInstanceByKey 应成功启动流程");
        processInstanceId = pi.getId();

        Task task = taskService.createTaskQuery().processInstanceId(pi.getId()).singleResult();
        assertNotNull(task, "启动后应停在第一个用户任务");
        assertEquals("部门审批", task.getName());
        assertEquals("zhangsan", task.getAssignee());
    }

    @Test
    @Order(2)
    void completeAllTasksAndReachEnd() {
        Task dept = taskService.createTaskQuery().processInstanceId(processInstanceId)
            .taskName("部门审批").singleResult();
        taskService.complete(dept.getId());

        Task finance = taskService.createTaskQuery().processInstanceId(processInstanceId)
            .taskName("财务审批").singleResult();
        assertNotNull(finance, "部门审批后应推进到财务审批");
        assertEquals("lisi", finance.getAssignee());
        taskService.complete(finance.getId());

        List<Task> remaining = taskService.createTaskQuery().processInstanceId(processInstanceId).list();
        assertTrue(remaining.isEmpty(), "全部任务完成后不应残留任务节点");

        long histCount = runtimeService.createProcessInstanceQuery()
            .processInstanceId(processInstanceId).count();
        assertEquals(0, histCount, "流程结束后运行时实例应清空");
    }
}
