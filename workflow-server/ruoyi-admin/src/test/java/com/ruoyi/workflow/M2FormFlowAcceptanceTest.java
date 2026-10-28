package com.ruoyi.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import com.ruoyi.RuoYiApplication;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.workflow.document.ApprovalFormDataDoc;
import com.ruoyi.workflow.domain.WfApprovalInstance;
import com.ruoyi.workflow.domain.WfFormDefinition;
import com.ruoyi.workflow.domain.WfFormVersion;
import com.ruoyi.workflow.mapper.WfApprovalInstanceMapper;
import com.ruoyi.workflow.mapper.WfFormDefinitionMapper;
import com.ruoyi.workflow.repository.ApprovalFormDataRepository;
import com.ruoyi.workflow.repository.FormSchemaVersionRepository;
import com.ruoyi.workflow.service.FormDataService;
import com.ruoyi.workflow.service.FormDefinitionService;

/**
 * M2 验收：动态表单全链（发布 → 填报 → Mongo JSON + MySQL 投影）。
 *
 * <p>验证：</p>
 * <ol>
 *     <li>创建表单定义 + 保存版本草稿 + 发布（schema_hash 校验、快照不可变）；</li>
 *     <li>表单数据提交（必填/类型校验）→ MongoDB approval_form_data 存 JSON + MySQL wf_approval_instance 投影；</li>
 *     <li>校验器拒绝缺必填/类型错误数据。</li>
 * </ol>
 *
 * <p>依赖：MySQL(workflow_dev)、MongoDB(workflow) 在线；WF_DB_PASSWORD/WF_MONGO_URI 注入。</p>
 */
@SpringBootTest(classes = RuoYiApplication.class, webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@ActiveProfiles("druid")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class M2FormFlowAcceptanceTest {

    @Autowired
    private FormDefinitionService formDefinitionService;

    @Autowired
    private FormDataService formDataService;

    @Autowired
    private WfFormDefinitionMapper definitionMapper;

    @Autowired
    private WfApprovalInstanceMapper approvalInstanceMapper;

    @Autowired
    private ApprovalFormDataRepository formDataRepository;

    @Autowired
    private FormSchemaVersionRepository schemaRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private static String formId;
    private static String versionId;
    private static String approvalInstanceId;

    /** 前置清理：删除上次运行的测试残留（M2 表单 expense_apply 及其投影/版本），保证可重跑。 */
    @Test
    @Order(0)
    void cleanupPreviousRun() {
        // 1. 子表（M2 不产生任务/操作记录，防御性删除防 FK 阻碍）
        jdbcTemplate.update("""
            delete from wf_approval_operation where approval_instance_id in (
                select id from wf_approval_instance where form_version_id in (
                    select id from wf_form_version where form_id in (
                        select id from wf_form_definition where form_code = 'expense_apply')))
            """);
        jdbcTemplate.update("""
            delete from wf_approval_action where approval_instance_id in (
                select id from wf_approval_instance where form_version_id in (
                    select id from wf_form_version where form_id in (
                        select id from wf_form_definition where form_code = 'expense_apply')))
            """);
        jdbcTemplate.update("""
            delete from wf_approval_task where approval_instance_id in (
                select id from wf_approval_instance where form_version_id in (
                    select id from wf_form_version where form_id in (
                        select id from wf_form_definition where form_code = 'expense_apply')))
            """);
        // 2. 审批单投影（formDataService.submit 双写落 MySQL）
        jdbcTemplate.update("""
            delete from wf_approval_instance where form_version_id in (
                select id from wf_form_version where form_id in (
                    select id from wf_form_definition where form_code = 'expense_apply'))
            """);
        // 3. 表单版本与定义
        jdbcTemplate.update("""
            delete from wf_form_version where form_id in (
                select id from wf_form_definition where form_code = 'expense_apply')
            """);
        jdbcTemplate.update("delete from wf_form_definition where form_code = 'expense_apply'");
    }

    @Test
    @Order(1)
    void createPublishForm() {
        // 1. 创建表单定义
        WfFormDefinition definition = formDefinitionService.createDefinition(
            "expense_apply", "费用报销申请", "admin");
        formId = definition.getId();
        assertEquals("DRAFT", definition.getStatus());

        // 2. 保存版本草稿（schema 落 Mongo）
        Map<String, Object> fieldApplicant = Map.of(
            "field", "applicant", "type", "user", "label", "申请人", "required", true);
        Map<String, Object> fieldAmount = Map.of(
            "field", "amount", "type", "money", "label", "报销金额", "required", true);
        Map<String, Object> fieldReason = Map.of(
            "field", "reason", "type", "textarea", "label", "事由", "required", false);
        Map<String, Object> schema = Map.of(
            "formCode", "expense_apply",
            "formName", "费用报销申请",
            "layout", Map.of("columns", 2),
            "fields", List.of(fieldApplicant, fieldAmount, fieldReason));

        WfFormVersion draft = formDefinitionService.saveDraft(formId, schema, "admin");
        versionId = draft.getId();
        assertEquals(1, draft.getVersionNo());
        assertNotNull(draft.getSchemaHash(), "schema_hash 应生成");

        // 3. 发布（快照不可变）
        WfFormVersion published = formDefinitionService.publish(formId, versionId, "admin");
        assertEquals("PUBLISHED", published.getStatus());
        assertNotNull(published.getPublishedAt());

        WfFormDefinition after = definitionMapper.selectById(formId);
        assertEquals("PUBLISHED", after.getStatus());
        assertEquals(1, after.getCurrentVersion());
    }

    @Test
    @Order(2)
    void submitFormDataDoubleWrite() {
        // 提交：必填校验通过 → Mongo JSON + MySQL 投影
        Map<String, Object> data = Map.of(
            "applicant", "user-001",
            "amount", 6800,
            "reason", "办公设备采购");

        WfApprovalInstance instance = formDataService.submit(
            versionId, data, "1", "admin", "费用报销-办公设备采购");
        approvalInstanceId = instance.getId();
        assertEquals("SUBMITTING", instance.getBusinessStatus());
        assertNotNull(instance.getApprovalNo());

        // MySQL 投影存在
        WfApprovalInstance projection = approvalInstanceMapper.selectById(approvalInstanceId);
        assertNotNull(projection, "MySQL 投影应存在");
        assertEquals("费用报销-办公设备采购", projection.getTitle());
        assertEquals("1", projection.getApplicantUserId());

        // Mongo JSON 存在且 approvalInstanceId 一致（规则 1）
        ApprovalFormDataDoc doc = formDataRepository.findByApprovalInstanceId(approvalInstanceId).orElse(null);
        assertNotNull(doc, "MongoDB 表单文档应存在");
        assertEquals(approvalInstanceId, doc.getApprovalInstanceId(), "Mongo approvalInstanceId 应与 MySQL 一致");
        assertEquals(versionId, doc.getFormVersionId(), "formVersionId 应固定指向发起版本");
        assertEquals(1, doc.getDataVersion());
        assertTrue(doc.getData() instanceof Map, "表单数据应为 JSON 对象");
    }

    @Test
    @Order(3)
    void validatorRejectsInvalidData() {
        // 必填缺失 → 拒绝
        Map<String, Object> missingRequired = Map.of(
            "reason", "没有申请人");
        try {
            formDataService.submit(versionId, missingRequired, "1", "admin", "缺必填项");
            throw new AssertionError("缺必填项应被拒绝");
        } catch (ServiceException expected) {
            assertTrue(expected.getMessage().contains("必填"), "错误信息应提示必填: " + expected.getMessage());
        }

        // 类型错误 → 拒绝
        Map<String, Object> badType = Map.of(
            "applicant", "user-001",
            "amount", "不是数字",
            "reason", "类型错误");
        try {
            formDataService.submit(versionId, badType, "1", "admin", "类型错误");
            throw new AssertionError("类型错误应被拒绝");
        } catch (ServiceException expected) {
            assertTrue(expected.getMessage().contains("类型错误"), "错误信息应提示类型: " + expected.getMessage());
        }
    }
}
