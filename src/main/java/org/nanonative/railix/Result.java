package org.nanonative.railix;

import berlin.yuna.typemap.model.LinkedTypeMap;
import berlin.yuna.typemap.model.TypeMapI;

import java.util.Optional;

public record Result(
    Outcome outcome,
    String message,
    int code,
    Throwable cause,
    LinkedTypeMap payload,
    LinkedTypeMap ctx
) {
  public static final int CODE_ABSENT = -1;

  public Result {
    if (outcome == null) {
      outcome = Outcome.UNEXPECTED;
    }
    if (message == null) {
      message = "";
    }
    if (payload == null) {
      payload = new LinkedTypeMap();
    }
    if (ctx == null) {
      ctx = new LinkedTypeMap();
    }
  }

  public static Result success(final TypeMapI<?> payload, final TypeMapI<?> ctx) {
    return new Result(Outcome.SUCCESS, "", CODE_ABSENT, null, snapshot(payload), snapshot(ctx));
  }

  public static Result error(final String message, final int code, final TypeMapI<?> payload, final TypeMapI<?> ctx) {
    return new Result(Outcome.ERROR, message, code, null, snapshot(payload), snapshot(ctx));
  }

  public static Result unexpected(final Throwable cause, final String message, final int code, final TypeMapI<?> payload,
      final TypeMapI<?> ctx) {
    return new Result(Outcome.UNEXPECTED, message, code, cause, snapshot(payload), snapshot(ctx));
  }

  public Optional<String> messageOpt() {
    return message == null || message.isBlank() ? Optional.empty() : Optional.of(message);
  }

  public Optional<Integer> codeOpt() {
    return code == CODE_ABSENT ? Optional.empty() : Optional.of(code);
  }

  public Optional<Throwable> causeOpt() {
    return Optional.ofNullable(cause);
  }

  static LinkedTypeMap snapshot(final TypeMapI<?> source) {
    return source == null ? new LinkedTypeMap() : new LinkedTypeMap(source);
  }
}
