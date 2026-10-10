package com.stelody.auth.web;

import jakarta.validation.ConstraintViolation;
import java.util.*;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import tools.jackson.core.JacksonException;

public final class InputErrors {
  private InputErrors() {}

  public static ApiFieldError field(String path, String code) {
    String message =
        switch (code) {
          case "REQUIRED" -> "필수 입력입니다";
          case "INVALID_FORMAT" -> "입력 형식을 확인해 주세요";
          case "INVALID_SIZE" -> "입력 길이 또는 항목 수를 확인해 주세요";
          case "OUT_OF_RANGE" -> "허용 범위를 확인해 주세요";
          case "DUPLICATE" -> "중복 항목을 제거해 주세요";
          default -> "입력 내용을 확인해 주세요";
        };
    return new ApiFieldError(path, code, message);
  }

  private static String code(String constraint) {
    if (constraint == null) return "INVALID_VALUE";
    return switch (constraint) {
      case "NotNull", "NotBlank", "NotEmpty" -> "REQUIRED";
      case "Size" -> "INVALID_SIZE";
      case "Min",
          "Max",
          "DecimalMin",
          "DecimalMax",
          "Positive",
          "PositiveOrZero",
          "Negative",
          "NegativeOrZero" ->
          "OUT_OF_RANGE";
      case "Pattern", "Email" -> "INVALID_FORMAT";
      default -> "INVALID_VALUE";
    };
  }

  private static String path(String value) {
    return value.replaceAll("\\.?<[^>]+>", "");
  }

  private static List<ApiFieldError> ordered(Collection<ApiFieldError> errors) {
    return errors.stream()
        .filter(e -> !e.field().isEmpty())
        .distinct()
        .sorted(Comparator.comparing(ApiFieldError::field).thenComparing(ApiFieldError::code))
        .toList();
  }

  public static List<ApiFieldError> violations(Set<? extends ConstraintViolation<?>> violations) {
    return ordered(
        violations.stream()
            .map(
                v ->
                    field(
                        path(v.getPropertyPath().toString()),
                        code(
                            v.getConstraintDescriptor()
                                .getAnnotation()
                                .annotationType()
                                .getSimpleName())))
            .toList());
  }

  public static List<ApiFieldError> from(Exception error) {
    if (error instanceof MethodArgumentNotValidException invalid)
      return ordered(
          invalid.getBindingResult().getFieldErrors().stream()
              .map(
                  e ->
                      field(
                          path(e.getField()),
                          e.isBindingFailure() ? "INVALID_FORMAT" : code(e.getCode())))
              .toList());
    if (error instanceof MethodArgumentTypeMismatchException invalid)
      return List.of(field(invalid.getName(), "INVALID_FORMAT"));
    if (error instanceof MissingServletRequestParameterException missing)
      return List.of(field(missing.getParameterName(), "REQUIRED"));
    // Only retain structural property/index references, never Jackson's message or value.
    for (Throwable cause = error.getCause(); cause != null; cause = cause.getCause()) {
      if (cause instanceof JacksonException jackson && !jackson.getPath().isEmpty()) {
        var path = new StringBuilder();
        for (var reference : jackson.getPath()) {
          if (reference.getPropertyName() != null) {
            if (!path.isEmpty()) path.append('.');
            path.append(reference.getPropertyName());
          } else if (reference.getIndex() >= 0)
            path.append('[').append(reference.getIndex()).append(']');
        }
        if (!path.isEmpty()) return List.of(field(path.toString(), "INVALID_FORMAT"));
      }
    }
    return List.of();
  }
}
