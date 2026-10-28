package com.ruoyi.workflow.controller;

import java.util.List;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.ruoyi.common.core.controller.BaseController;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.utils.SecurityUtils;
import com.ruoyi.workflow.domain.WfFormDefinition;
import com.ruoyi.workflow.domain.WfFormVersion;
import com.ruoyi.workflow.service.FormDefinitionService;

/**
 * 表单定义管理（PRD 5.2–5.4）。
 */
@RestController
@RequestMapping("/workflow/forms")
public class WfFormDefinitionController extends BaseController {

    private final FormDefinitionService formDefinitionService;

    public WfFormDefinitionController(FormDefinitionService formDefinitionService) {
        this.formDefinitionService = formDefinitionService;
    }

    /**
     * 创建表单定义。
     */
    @PostMapping
    public AjaxResult create(@RequestBody WfFormDefinition definition) {
        WfFormDefinition created = formDefinitionService.createDefinition(
            definition.getFormCode(), definition.getFormName(), SecurityUtils.getUsername());
        return success(created);
    }

    /**
     * 表单定义列表。
     */
    @GetMapping("/list")
    public AjaxResult list(WfFormDefinition query) {
        List<WfFormDefinition> list = formDefinitionService.listDefinitions(query);
        return success(list);
    }

    /**
     * 表单定义详情。
     */
    @GetMapping("/{formId}")
    public AjaxResult detail(@PathVariable String formId) {
        return success(formDefinitionService.getDefinition(formId));
    }

    /**
     * 保存版本草稿（schema 落 MongoDB，不发布）。
     */
    @PostMapping("/{formId}/versions/draft")
    public AjaxResult saveDraft(@PathVariable String formId, @RequestBody Object schema) {
        WfFormVersion version = formDefinitionService.saveDraft(formId, schema, SecurityUtils.getUsername());
        return success(version);
    }

    /**
     * 发布版本（快照不可变）。
     */
    @PutMapping("/{formId}/versions/{versionId}/publish")
    public AjaxResult publish(@PathVariable String formId, @PathVariable String versionId) {
        WfFormVersion version = formDefinitionService.publish(formId, versionId, SecurityUtils.getUsername());
        return success(version);
    }

    /**
     * 版本列表。
     */
    @GetMapping("/{formId}/versions")
    public AjaxResult versions(@PathVariable String formId) {
        return success(formDefinitionService.listVersions(formId));
    }

    /**
     * 当前已发布版本 schema（供渲染/填报）。
     */
    @GetMapping("/{formId}/schema")
    public AjaxResult publishedSchema(@PathVariable String formId) {
        return success(formDefinitionService.getPublishedSchema(formId));
    }
}
