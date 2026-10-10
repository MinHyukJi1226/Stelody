package com.stelody.review.web;

import com.stelody.auth.web.ApiProblems;
import com.stelody.review.controller.ReviewController;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice(assignableTypes = ReviewController.class)
public class ReviewExceptionHandler {
  private final ApiProblems problems;

  public ReviewExceptionHandler(ApiProblems problems) {
    this.problems = problems;
  }

  @ExceptionHandler(ReviewException.class)
  public void business(
      ReviewException error, HttpServletRequest request, HttpServletResponse response)
      throws IOException {
    problems.write(request, response, error.status(), error.code(), error.getMessage());
  }

  @ExceptionHandler({
    OptimisticLockingFailureException.class,
    jakarta.persistence.OptimisticLockException.class
  })
  public void conflict(Exception error, HttpServletRequest request, HttpServletResponse response)
      throws IOException {
    business(ReviewException.conflict(), request, response);
  }

  @ExceptionHandler({
    MethodArgumentNotValidException.class,
    MethodArgumentTypeMismatchException.class,
    HttpMessageNotReadableException.class
  })
  public void invalid(Exception error, HttpServletRequest request, HttpServletResponse response)
      throws IOException {
    var invalid = ReviewException.invalid();
    problems.writeInvalid(request, response, invalid.code(), invalid.getMessage(), error);
  }
}
