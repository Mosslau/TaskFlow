package com.taskflow.stats;

import com.taskflow.common.Result;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class PingController {

    @GetMapping("/stats/api/v1/ping")
    public Result<String> ping() {
        return Result.ok("pong");
    }
}
