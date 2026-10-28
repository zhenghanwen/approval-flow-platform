package com.ruoyi.workflow.controller;

import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.ruoyi.common.core.controller.BaseController;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.utils.SecurityUtils;
import com.ruoyi.workflow.document.ApprovalFormDataDoc;
import com.ruoyi.workflow.domain.WfApprovalInstance;
import com.ruoyi.workflow.service.FormDataService;

/**
 * 表单数据存取（PRD 6.2：草稿保存 / 提交 / 读取）。
 */
@RestController
@RequestMapping("/workflow/form-data")
public class WfFormDataController extends BaseController {

    private final FormDataService formDataService;

    public WfFormDataController(FormDataService formDataService) {
        this.formDataService = formDataService;
    }

    /**
     * 请求体：{ formVersionId, data, title }
     */
    public static class DraftRequest {
        public String formVersionId;
        public Object data;
        public String title;
    }

    /**
     * 保存草稿（类型校验，不强制必填）。
     */
    @PostMapping("/draft")
    public AjaxResult saveDraft(@RequestBody DraftRequest request) {
        WfApprovalInstance instance = formDataService.saveDraft(
            request.formVersionId, request.data,
            SecurityUtils.getUserId().toString(), SecurityUtils.getUsername(), request.title);
        return success(instance);
    }

    /**
     * 提交（强制必填 + 类型校验）。
     */
    @PostMapping("/submit")
    public AjaxResult submit(@RequestBody DraftRequest request) {
        WfApprovalInstance instance = formDataService.submit(
            request.formVersionId, request.data,
            SecurityUtils.getUserId().toString(), SecurityUtils.getUsername(), request.title);
        return success(instance);
    }

    /**
     * 读取审批单完整表单 JSON。
     */
    @GetMapping("/{approvalInstanceId}")
    public AjaxResult getFormData(@PathVariable String approvalInstanceId) {
        ApprovalFormDataDoc doc = formDataService.getFormData(approvalInstanceId);
        Map<String, Object> result = new java.util.LinkedHashMap<>(5);
        result.put("approvalInstanceId", doc.getApprovalInstanceId());
        result.put("formVersionId", doc.getFormVersionId());
        result.put("schemaHash", doc.getSchemaHash());
        result.put("dataVersion", doc.getDataVersion());
        result.put("data", doc.getData());
        return success(result);
    }
}
