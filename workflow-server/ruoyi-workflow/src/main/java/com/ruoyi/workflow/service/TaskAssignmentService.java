package com.ruoyi.workflow.service;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.workflow.bpmn.BpmnValidationService;

/**
 * 审批人解析服务（PRD 12.2 六种来源 + 找不到人 BLOCK 策略）。
 *
 * <p>节点配置在流程 config_json 中声明（v1 简化：审批人来源挂在 UserTask 的
 * extensionElements 上，解析时按节点读取）。本服务接收申请人、表单数据与流程上下文，
 * 输出节点 ID → 用户 ID 集合 的映射，再注入流程变量 {@code wf_users_<nodeId>} 供多实例使用。</p>
 *
 * <p>审批人来源（assignType）：</p>
 * <ul>
 *     <li>USER：指定用户（assignValue 逗号分隔用户ID）；</li>
 *     <li>ROLE：指定角色（按 sys_user_role 关联）；</li>
 *     <li>DIRECT_MANAGER：发起人直属主管（sys_user.manager_user_id）；</li>
 *     <li>DEPT_LEADER：发起人部门负责人（sys_dept.leader）；</li>
 *     <li>FORM_FIELD：表单人员字段（表单数据中某字段值为用户ID）；</li>
 *     <li>EXTERNAL_API：外部接口返回的人员（骨架，调用 wf_external_api 已登记接口）。</li>
 * </ul>
 *
 * <p>找不到审批人 → 抛 ServiceException（BLOCK 策略，禁止默默跳过审批节点，PRD 12.2）。</p>
 */
@Service
public class TaskAssignmentService {

    public static final String TYPE_USER = "USER";
    public static final String TYPE_ROLE = "ROLE";
    public static final String TYPE_DIRECT_MANAGER = "DIRECT_MANAGER";
    public static final String TYPE_DEPT_LEADER = "DEPT_LEADER";
    public static final String TYPE_FORM_FIELD = "FORM_FIELD";
    public static final String TYPE_EXTERNAL_API = "EXTERNAL_API";

    private final JdbcTemplate jdbcTemplate;
    private final ExternalApiService externalApiService;

    public TaskAssignmentService(JdbcTemplate jdbcTemplate, ExternalApiService externalApiService) {
        this.jdbcTemplate = jdbcTemplate;
        this.externalApiService = externalApiService;
    }

    /**
     * 解析流程中所有审批节点的审批人。
     *
     * @param nodeAssignments 节点ID → 审批人配置（assignType/assignValue/formField）
     * @param applicantUserId 申请人ID
     * @param applicantDeptId 申请人部门ID
     * @param formData        表单数据（FORM_FIELD 来源用）
     * @return 节点ID → 用户ID 集合
     */
    public Map<String, List<String>> resolveAll(Map<String, Map<String, Object>> nodeAssignments,
                                                String applicantUserId, String applicantDeptId,
                                                Map<String, Object> formData) {
        if (nodeAssignments == null || nodeAssignments.isEmpty()) {
            return Collections.emptyMap();
        }
        return nodeAssignments.entrySet().stream()
            .collect(Collectors.toMap(Map.Entry::getKey,
                e -> resolve(e.getKey(), e.getValue(), applicantUserId, applicantDeptId, formData)));
    }

    /**
     * 解析单节点审批人。找不到人抛异常（BLOCK）。
     */
    public List<String> resolve(String nodeId, Map<String, Object> config,
                                String applicantUserId, String applicantDeptId,
                                Map<String, Object> formData) {
        if (config == null) {
            throw new ServiceException("节点 [" + nodeId + "] 缺少审批人配置");
        }
        String assignType = String.valueOf(config.getOrDefault("assignType", TYPE_USER));
        String assignValue = config.get("assignValue") == null ? null : String.valueOf(config.get("assignValue"));
        String formField = config.get("formField") == null ? null : String.valueOf(config.get("formField"));

        List<String> users;
        switch (assignType) {
            case TYPE_USER:
                users = splitUsers(assignValue);
                break;
            case TYPE_ROLE:
                users = resolveByRole(assignValue);
                break;
            case TYPE_DIRECT_MANAGER:
                users = resolveDirectManager(applicantUserId);
                break;
            case TYPE_DEPT_LEADER:
                users = resolveDeptLeader(applicantDeptId);
                break;
            case TYPE_FORM_FIELD:
                users = resolveFormField(formData, formField);
                break;
            case TYPE_EXTERNAL_API:
                users = resolveExternalApi(assignValue, applicantUserId);
                break;
            default:
                throw new ServiceException("节点 [" + nodeId + "] 审批人来源不支持: " + assignType);
        }
        if (users.isEmpty()) {
            throw new ServiceException("节点 [" + nodeId + "] 找不到审批人，已阻止提交（BLOCK）");
        }
        return users;
    }

    private List<String> splitUsers(String assignValue) {
        if (assignValue == null || assignValue.isBlank()) {
            return Collections.emptyList();
        }
        return java.util.Arrays.stream(assignValue.split(","))
            .map(String::trim).filter(s -> !s.isEmpty()).collect(Collectors.toList());
    }

    /**
     * 指定角色：sys_user_role 关联查用户。
     */
    private List<String> resolveByRole(String roleKey) {
        if (roleKey == null || roleKey.isBlank()) {
            return Collections.emptyList();
        }
        String sql = """
            select u.user_id from sys_user u
            join sys_user_role ur on u.user_id = ur.user_id
            join sys_role r on r.role_id = ur.role_id
            where r.role_key = ? and u.status = '0' and u.del_flag = '0'
            """;
        return jdbcTemplate.queryForList(sql, String.class, roleKey);
    }

    /**
     * 发起人直属主管：sys_user.manager_user_id。
     */
    private List<String> resolveDirectManager(String applicantUserId) {
        if (applicantUserId == null) {
            return Collections.emptyList();
        }
        List<String> managers = jdbcTemplate.queryForList(
            "select manager_user_id from sys_user where user_id = ? and status = '0' and del_flag = '0'",
            String.class, applicantUserId);
        return managers.stream().filter(m -> m != null && !m.isBlank()).collect(Collectors.toList());
    }

    /**
     * 发起人部门负责人：sys_dept.leader。
     */
    private List<String> resolveDeptLeader(String applicantDeptId) {
        if (applicantDeptId == null) {
            return Collections.emptyList();
        }
        List<String> leaders = jdbcTemplate.queryForList(
            "select leader from sys_dept where dept_id = ? and status = '0' and del_flag = '0'",
            String.class, applicantDeptId);
        return leaders.stream().filter(l -> l != null && !l.isBlank()).collect(Collectors.toList());
    }

    /**
     * 表单人员字段：表单数据中某字段值为用户ID（支持字符串或对象 {id: ...}）。
     */
    @SuppressWarnings("unchecked")
    private List<String> resolveFormField(Map<String, Object> formData, String formField) {
        if (formData == null || formField == null || !formData.containsKey(formField)) {
            return Collections.emptyList();
        }
        Object value = formData.get(formField);
        if (value instanceof String s && !s.isBlank()) {
            return List.of(s);
        }
        if (value instanceof Map m && m.get("id") != null) {
            return List.of(String.valueOf(m.get("id")));
        }
        return Collections.emptyList();
    }

    /**
     * 外部接口返回的人员（骨架）：调用已登记接口，期望响应含 users 数组（user.id）。
     */
    private List<String> resolveExternalApi(String apiCode, String applicantUserId) {
        if (apiCode == null || apiCode.isBlank()) {
            return Collections.emptyList();
        }
        // v1 骨架：仅支持按已登记接口编码调用，响应体 { "users": [ { "id": "..." } ] }
        String sql = "select id from wf_external_api where api_code = ? and status = 'ENABLED'";
        List<String> ids = jdbcTemplate.queryForList(sql, String.class, apiCode);
        if (ids.isEmpty()) {
            return Collections.emptyList();
        }
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> result = (Map<String, Object>) externalApiService.testCall(ids.get(0),
                Map.of("applicantUserId", applicantUserId), "system");
            Object usersObj = result.get("body");
            // 骨架实现：body 为 JSON 字符串时尝试解析 users
            if (usersObj instanceof String body) {
                Object parsed = new com.fasterxml.jackson.databind.ObjectMapper().readValue(body,
                    java.util.Map.class);
                if (parsed instanceof Map<?, ?> m && m.get("users") instanceof List<?> list) {
                    return list.stream()
                        .filter(u -> u instanceof Map)
                        .map(u -> String.valueOf(((Map<?, ?>) u).get("id")))
                        .filter(id -> id != null && !"null".equals(id))
                        .collect(Collectors.toList());
                }
            }
            return Collections.emptyList();
        } catch (Exception e) {
            throw new ServiceException("外部接口审批人解析失败: " + e.getMessage());
        }
    }
}
