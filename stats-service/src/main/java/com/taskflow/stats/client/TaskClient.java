package com.taskflow.stats.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.Map;

/**
 * task-service 的 Feign 客户端：rebuild 回填时拉全量任务。
 *
 * <p>身份头由 FeignConfig 透传：rebuild 发生在 HTTP 线程，调用者是 admin，
 * task-service 列表接口（#19，无权限点注解）对 admin / viewAll 不加可见性过滤，
 * 因此能拿到全量任务（含子任务）。</p>
 */
@FeignClient(name = "task-service", path = "/task/api/v1")
public interface TaskClient {

    /**
     * 任务列表（接口 #19）：分页拉取，item 含 id/taskNo/status/priority/assigneeId/
     * creatorId/parentId/dueAt/createdAt/updatedAt。
     *
     * @param page 页码（1 起）
     * @param size 每页条数
     * @return 信封包裹的 {list, total, page, size}
     */
    @GetMapping("/tasks")
    Map<String, Object> list(@RequestParam("page") int page, @RequestParam("size") int size);
}
