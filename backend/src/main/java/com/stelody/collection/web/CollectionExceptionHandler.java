package com.stelody.collection.web;

import com.stelody.auth.web.ApiProblems;
import com.stelody.collection.controller.CollectionController;
import jakarta.servlet.http.*;
import java.io.IOException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice(assignableTypes = CollectionController.class)
public class CollectionExceptionHandler {
  private final ApiProblems problems;

  public CollectionExceptionHandler(ApiProblems problems) {
    this.problems = problems;
  }

  @ExceptionHandler(CollectionException.class)
  public void business(
      CollectionException error, HttpServletRequest request, HttpServletResponse response)
      throws IOException {
    problems.write(request, response, error.status(), error.code(), error.getMessage());
  }

  @ExceptionHandler({
    MethodArgumentNotValidException.class,
    MethodArgumentTypeMismatchException.class,
    HttpMessageNotReadableException.class
  })
  public void invalid(Exception error, HttpServletRequest request, HttpServletResponse response)
      throws IOException {
    var invalid = CollectionException.invalid();
    problems.writeInvalid(request, response, invalid.code(), invalid.getMessage(), error);
  }

  @ExceptionHandler(DuplicateKeyException.class)
  public void duplicate(Exception error, HttpServletRequest request, HttpServletResponse response)
      throws IOException {
    business(new CollectionException(409, "COLLECTION_RETRY_ALREADY_ACTIVE"), request, response);
  }
}
