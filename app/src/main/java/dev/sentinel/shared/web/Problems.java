package dev.sentinel.shared.web;

import java.net.URI;

import dev.sentinel.observability.TraceContext;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

/**
 * RFC 9457 problem details shared by the API, security handlers and the rate limiter. Every problem carries the
 * {@code trace_id} so a support request can be correlated with traces and logs without exposing internals.
 */
public final class Problems {

    private Problems() {
    }

    public static ProblemDetail of(HttpStatus status, String slug, String title, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setType(type(slug));
        problem.setTitle(title);
        return withTraceId(problem);
    }

    public static ProblemDetail withTraceId(ProblemDetail problem) {
        TraceContext.currentTraceId().ifPresent(traceId -> problem.setProperty("trace_id", traceId));
        return problem;
    }

    public static URI type(String slug) {
        return URI.create("urn:problem-type:sentinel:" + slug);
    }
}
