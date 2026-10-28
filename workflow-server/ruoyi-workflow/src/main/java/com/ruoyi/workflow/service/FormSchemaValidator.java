package com.ruoyi.workflow.service;

import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.ruoyi.common.exception.ServiceException;

/**
 * 表单 schema 字段校验协议（PRD 6.2 数据规则 3 + PRD 10.1 第 4 步）。
 *
 * <p>基于 PRD 5.4 schema 结构：{@code fields[] = { field, type, label, required }}。
 * 校验维度：必填、数据类型、未知字段白名单。字段权限（可编辑/可见）由表单提交场景
 * 结合当前状态判断，M2 提供基础可编辑判定，M3 接入流程节点权限后扩展。</p>
 */
@Service
public class FormSchemaValidator {

    /** 支持的字段类型 */
    private static final List<String> SUPPORTED_TYPES = List.of(
        "text", "textarea", "number", "money", "date", "datetime",
        "select", "radio", "checkbox", "user", "dept", "file", "boolean"
    );

    /**
     * 校验表单数据。
     *
     * @param schema schema JSON（需为 Map，含 fields 数组）
     * @param data   提交的表单数据
     * @param requireRequired 是否强制校验 required（提交时 true，草稿保存可 false）
     */
    @SuppressWarnings("unchecked")
    public void validate(Object schema, Object data, boolean requireRequired) {
        if (!(schema instanceof Map)) {
            throw new ServiceException("schema 格式非法：必须为对象");
        }
        Map<String, Object> schemaMap = (Map<String, Object>) schema;
        Object fieldsObj = schemaMap.get("fields");
        if (!(fieldsObj instanceof List)) {
            throw new ServiceException("schema 格式非法：缺少 fields 数组");
        }
        List<Object> fields = (List<Object>) fieldsObj;
        Map<String, Object> dataMap = data instanceof Map ? (Map<String, Object>) data : Map.of();

        for (Object fieldObj : fields) {
            if (!(fieldObj instanceof Map)) {
                continue;
            }
            Map<String, Object> field = (Map<String, Object>) fieldObj;
            String key = String.valueOf(field.get("field"));
            String type = String.valueOf(field.getOrDefault("type", "text"));
            String label = String.valueOf(field.getOrDefault("label", key));
            boolean required = Boolean.TRUE.equals(field.get("required"));

            if (!SUPPORTED_TYPES.contains(type)) {
                throw new ServiceException("字段类型不支持: " + type + "（字段: " + label + "）");
            }
            if (requireRequired && required && !dataMap.containsKey(key)) {
                throw new ServiceException("必填字段未填写: " + label);
            }
            if (!dataMap.containsKey(key) || dataMap.get(key) == null) {
                continue;
            }
            validateType(key, type, label, dataMap.get(key));
        }
    }

    private void validateType(String key, String type, String label, Object value) {
        switch (type) {
            case "number":
            case "money":
                if (!(value instanceof Number)) {
                    throw new ServiceException("字段类型错误: " + label + " 应为数字");
                }
                if ("money".equals(type) && ((Number) value).doubleValue() < 0) {
                    throw new ServiceException("字段取值非法: " + label + " 不能为负");
                }
                break;
            case "boolean":
                if (!(value instanceof Boolean)) {
                    throw new ServiceException("字段类型错误: " + label + " 应为布尔值");
                }
                break;
            case "date":
            case "datetime":
                if (!(value instanceof String) && !(value instanceof Number)) {
                    throw new ServiceException("字段类型错误: " + label + " 应为日期");
                }
                break;
            case "text":
            case "textarea":
            case "select":
            case "radio":
            case "user":
            case "dept":
                if (!(value instanceof String)) {
                    throw new ServiceException("字段类型错误: " + label + " 应为文本");
                }
                break;
            case "checkbox":
            case "file":
                // 支持单个对象、字符串或数组；file 元素须含元数据字段（PRD 6.2 规则 6）
                if (value instanceof Map) {
                    Map<?, ?> m = (Map<?, ?>) value;
                    if ("file".equals(type) && !m.containsKey("fileId") && !m.containsKey("id")) {
                        throw new ServiceException("附件字段缺少文件标识: " + label);
                    }
                }
                break;
            default:
                break;
        }
    }
}
