package com.taskflow.task.controller;

import com.taskflow.common.Result;
import com.taskflow.task.service.TaskCalendarService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 日程聚合接口（接口 #39，PRD 4.2.2）。
 *
 * <p>路径 /tasks/calendar 与 TaskController 的 /tasks/{id} 共存：
 * Spring MVC 精确字面量路径优先于模板路径，不会落入 detail()。</p>
 */
@RestController
@RequestMapping("/task/api/v1/tasks")
public class TaskCalendarController {

    private final TaskCalendarService calendarService;

    public TaskCalendarController(TaskCalendarService calendarService) {
        this.calendarService = calendarService;
    }

    /** 月度日程聚合：按到期日分组当月任务（含子任务，同款可见性过滤） */
    @GetMapping("/calendar")
    public Result<List<Map<String, Object>>> calendar(@RequestParam String month) {
        return Result.ok(calendarService.calendar(month));
    }
}
