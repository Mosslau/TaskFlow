package com.taskflow.stats.controller;

import com.taskflow.common.BizException;
import com.taskflow.common.ErrorCode;
import com.taskflow.common.Result;
import com.taskflow.stats.config.AuthContext;
import com.taskflow.stats.config.RequirePerm;
import com.taskflow.stats.service.OverviewService;
import com.taskflow.stats.service.StatsAggregateService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 统计接口（接口文档 5.2，权限点 viewStats）。
 */
@RestController
@RequestMapping("/stats/api/v1")
public class StatsController {

    private final OverviewService overviewService;
    private final StatsAggregateService aggregateService;

    public StatsController(OverviewService overviewService, StatsAggregateService aggregateService) {
        this.overviewService = overviewService;
        this.aggregateService = aggregateService;
    }

    /**
     * 统计总览（接口 #47）：KPI 六项 + 趋势 + 状态/优先级分布 + 人员负载。
     *
     * @param range all / month / week / custom（默认 all）
     * @param start 自定义起始日（custom 必填，含当日）
     * @param end   自定义截止日（custom 必填，含当日）
     * @return 统计总览 data
     */
    @GetMapping("/overview")
    @RequirePerm("viewStats")
    public Result<Map<String, Object>> overview(
            @RequestParam(defaultValue = "all") String range,
            @RequestParam(required = false) String start,
            @RequestParam(required = false) String end) {
        return Result.ok(overviewService.overview(range, start, end));
    }

    /**
     * 聚合回填（admin 专用）：清空三张聚合表后经 Feign 拉 task-service 全量任务重算。
     * 用于事件上线前的历史任务、以及消费漂移后的最终一致性修复。
     *
     * @return 重算摘要 {tasks, days, overdue}
     */
    @PostMapping("/rebuild")
    @RequirePerm("viewStats")
    public Result<Map<String, Object>> rebuild() {
        if (!"admin".equals(AuthContext.getRoleKey())) {
            throw new BizException(ErrorCode.PERMISSION_DENIED, "rebuild 仅 admin 角色可用",
                    Map.of("roleKey", String.valueOf(AuthContext.getRoleKey())));
        }
        return Result.ok(aggregateService.rebuild());
    }
}
