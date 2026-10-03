package com.stelody.export.web;

import com.stelody.auth.web.ApiProblems;
import jakarta.servlet.http.*;
import java.io.IOException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice(basePackages = "com.stelody.export.controller")
public class ExportExceptionHandler {
  private final ApiProblems problems;

  public ExportExceptionHandler(ApiProblems problems) {
    this.problems = problems;
  }

  @ExceptionHandler(ExportException.class)
  void export(ExportException e, HttpServletRequest request, HttpServletResponse response)
      throws IOException {
    response.setHeader("Referrer-Policy", "no-referrer");
    problems.write(request, response, e.status(), e.code(), e.getMessage());
  }

  @ExceptionHandler({
    MethodArgumentNotValidException.class,
    HttpMessageNotReadableException.class,
    MethodArgumentTypeMismatchException.class,
    MissingServletRequestParameterException.class
  })
  void invalid(HttpServletRequest request, HttpServletResponse response) throws IOException {
    export(ExportException.invalid(), request, response);
  }
}
