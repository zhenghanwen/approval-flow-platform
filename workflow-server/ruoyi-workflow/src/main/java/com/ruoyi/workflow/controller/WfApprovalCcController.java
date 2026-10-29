package com.ruoyi.workflow.controller;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.ruoyi.common.core.controller.BaseController;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.utils.SecurityUtils;
import com.ruoyi.workflow.domain.WfApprovalCcVo;
import com.ruoyi.workflow.service.ApprovalCcService;

/**
 * 抄送我的（P7）。
 */
@RestController
@RequestMapping("/workflow/cc")
public class WfApprovalCcController extends BaseController {

    private final ApprovalCcService approvalCcService;

    public WfApprovalCcController(ApprovalCcService approvalCcService) {
        this.approvalCcService = approvalCcService;
    }

    /**
     * 我的抄送列表。
     */
    @GetMapping("/list")
    public AjaxResult list() {
        List<WfApprovalCcVo> rows = approvalCcService.listMine(SecurityUtils.getUserId().toString());
        return success(rows);
    }

    /**
     * 标记已读。
     */
    @PostMapping("/{id}/read")
    public AjaxResult markRead(@PathVariable String id) {
        approvalCcService.markRead(id, SecurityUtils.getUserId().toString());
        return success();
    }
}
