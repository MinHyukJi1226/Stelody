package com.stelody.auth.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
public class ApiProblems {
  private final ObjectMapper mapper;

  public ApiProblems(ObjectMapper mapper) {
    this.mapper = mapper;
  }

  public void write(
      HttpServletRequest request,
      HttpServletResponse response,
      int status,
      String code,
      String title)
      throws IOException {
    String traceId = UUID.randomUUID().toString();
    response.setStatus(status);
    response.setContentType("application/problem+json");
    response.setHeader("Cache-Control", "no-store");
    response.setHeader("X-Trace-Id", traceId);
    mapper.writeValue(
        response.getOutputStream(),
        Map.of(
            "type",
            "about:blank",
            "title",
            title,
            "status",
            status,
            "instance",
            request.getRequestURI(),
            "code",
            code,
            "fieldErrors",
            List.of(),
            "traceId",
            traceId));
  }
}
