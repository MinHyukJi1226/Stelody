package com.stelody.catalog.web;

import com.stelody.auth.web.ApiProblems;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice(
    basePackages = {"com.stelody.song.controller", "com.stelody.member.controller"})
public class CatalogExceptionHandler {
  private final ApiProblems problems;

  public CatalogExceptionHandler(ApiProblems problems) {
    this.problems = problems;
  }

  @ExceptionHandler(CatalogException.class)
  void catalog(CatalogException exception, HttpServletRequest request, HttpServletResponse response)
      throws IOException {
    problems.write(request, response, exception.status(), exception.code(), exception.getMessage());
  }

  @ExceptionHandler(MethodArgumentTypeMismatchException.class)
  void invalid(Exception error, HttpServletRequest request, HttpServletResponse response)
      throws IOException {
    var invalid = CatalogException.invalid();
    problems.writeInvalid(request, response, invalid.code(), invalid.getMessage(), error);
  }
}
