package com.stelody.favorite.web;

import com.stelody.auth.web.ApiProblems;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice(basePackages = "com.stelody.favorite.controller")
public class FavoriteExceptionHandler {
  private final ApiProblems problems;

  public FavoriteExceptionHandler(ApiProblems problems) {
    this.problems = problems;
  }

  @ExceptionHandler(FavoriteException.class)
  void favorite(
      FavoriteException exception, HttpServletRequest request, HttpServletResponse response)
      throws IOException {
    problems.write(request, response, exception.status(), exception.code(), exception.getMessage());
  }

  @ExceptionHandler(MethodArgumentTypeMismatchException.class)
  void invalid(Exception error, HttpServletRequest request, HttpServletResponse response)
      throws IOException {
    var invalid = FavoriteException.invalid();
    problems.writeInvalid(request, response, invalid.code(), invalid.getMessage(), error);
  }
}
