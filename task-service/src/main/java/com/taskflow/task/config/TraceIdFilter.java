package com.taskflow.task.config;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.UUID;

/**
 * 链路追踪过滤器（M7 可观测性）：把网关生成/透传的 X-Trace-Id 放进 MDC，
 * 供 logback-spring.xml 的 %X{traceId} 在每条日志中打出。
 *
 * <p>处理流程：</p>
 * <ol>
 *   <li>取请求头 X-Trace-Id；缺失（绕过网关直连本服务，如本地排障）则本地生成 UUID</li>
 *   <li>{@code MDC.put("traceId", ...)}，请求结束在 finally 中清理，防 Tomcat 线程池串号
 *       （与 AuthContext 的 ThreadLocal 清理同思路）</li>
 *   <li>响应头回写 X-Trace-Id，便于调用方/前端拿到本次链路标识</li>
 * </ol>
 *
 * <p>顺序取最高优先级：保证后续 MVC 拦截器、Controller、异常处理打出的日志都带 traceId。</p>
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE) // 最高优先级：早于其它 Servlet Filter 与 MVC 拦截器执行
public class TraceIdFilter implements Filter {

    /** 链路标识请求/响应头（与网关 TraceIdFilter 约定一致） */
    public static final String TRACE_ID_HEADER = "X-Trace-Id";

    /** MDC key：与 logback-spring.xml 中的 %X{traceId} 对应 */
    public static final String TRACE_ID_MDC_KEY = "traceId";

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        String traceId = null;
        if (request instanceof HttpServletRequest httpRequest) {
            traceId = httpRequest.getHeader(TRACE_ID_HEADER);
        }
        if (traceId == null || traceId.isBlank()) {
            // 非网关链路（直连服务）本地补一个，保证日志始终有链路标识可查
            traceId = UUID.randomUUID().toString().replace("-", "");
        }

        MDC.put(TRACE_ID_MDC_KEY, traceId);
        try {
            if (response instanceof HttpServletResponse httpResponse) {
                httpResponse.setHeader(TRACE_ID_HEADER, traceId);
            }
            chain.doFilter(request, response);
        } finally {
            // 必须清理：线程复用下残留 MDC 会污染下一次请求的日志
            MDC.remove(TRACE_ID_MDC_KEY);
        }
    }
}
