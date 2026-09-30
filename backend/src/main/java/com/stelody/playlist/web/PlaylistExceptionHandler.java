package com.stelody.playlist.web;

import com.stelody.auth.web.ApiProblems;
import jakarta.persistence.OptimisticLockException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice(basePackages = "com.stelody.playlist.controller")
public class PlaylistExceptionHandler {
  private final ApiProblems problems;

  public PlaylistExceptionHandler(ApiProblems problems) {
    this.problems = problems;
  }

  @ExceptionHandler(PlaylistException.class)
  void playlist(
      PlaylistException exception, HttpServletRequest request, HttpServletResponse response)
      throws IOException {
    problems.write(request, response, exception.status(), exception.code(), exception.getMessage());
  }

  @ExceptionHandler({
    MethodArgumentTypeMismatchException.class,
    MethodArgumentNotValidException.class,
    HttpMessageNotReadableException.class,
    MissingServletRequestParameterException.class
  })
  void invalid(HttpServletRequest request, HttpServletResponse response) throws IOException {
    playlist(PlaylistException.invalid(), request, response);
  }

  @ExceptionHandler({OptimisticLockingFailureException.class, OptimisticLockException.class})
  void changed(HttpServletRequest request, HttpServletResponse response) throws IOException {
    playlist(PlaylistException.changed(), request, response);
  }
}
