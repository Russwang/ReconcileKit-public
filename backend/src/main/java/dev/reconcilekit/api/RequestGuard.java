package dev.reconcilekit.api;

import java.io.*;
import java.util.Map;
import java.util.UUID;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

/** Correlation and a streaming body bound, including requests without Content-Length. */
@Component
public class RequestGuard extends OncePerRequestFilter {
    public static final int BODY_LIMIT = 2 * 1024 * 1024;
    private final ObjectMapper mapper;
    public RequestGuard(ObjectMapper mapper) { this.mapper = mapper; }
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String supplied = request.getHeader("X-Request-ID");
        String id = supplied != null && supplied.matches("[A-Za-z0-9_.:-]{1,100}") ? supplied : UUID.randomUUID().toString();
        request.setAttribute("request_id", id);
        response.setHeader("X-Request-ID", id);
        MDC.put("request_id", id);
        try {
            if ("POST".equals(request.getMethod())) {
                byte[] body = request.getInputStream().readNBytes(BODY_LIMIT + 1);
                if (body.length > BODY_LIMIT) {
                    response.setStatus(413); response.setContentType("application/json");
                    response.getWriter().write(mapper.writeValueAsString(Map.of("code", "BODY_TOO_LARGE", "message", "Request body exceeds 2 MiB", "request_id", id)));
                    return;
                }
                chain.doFilter(new CachedBody(request, body), response);
            } else chain.doFilter(request, response);
        } finally { MDC.remove("request_id"); }
    }
    private static final class CachedBody extends HttpServletRequestWrapper {
        private final byte[] body;
        CachedBody(HttpServletRequest request, byte[] body) { super(request); this.body = body; }
        @Override public ServletInputStream getInputStream() {
            var input = new ByteArrayInputStream(body);
            return new ServletInputStream() {
                @Override public int read() { return input.read(); }
                @Override public boolean isFinished() { return input.available() == 0; }
                @Override public boolean isReady() { return true; }
                @Override public void setReadListener(ReadListener listener) { throw new UnsupportedOperationException("Synchronous MVC body"); }
            };
        }
        @Override public BufferedReader getReader() { return new BufferedReader(new InputStreamReader(getInputStream(), java.nio.charset.StandardCharsets.UTF_8)); }
    }
}
