package org.nanonative.railix.log;

public enum LogLevel {
  TRACE,
  DEBUG,
  INFO,
  WARN,
  ERROR;

  public boolean enabled(final LogLevel configured) {
    return this.ordinal() >= configured.ordinal();
  }
}

