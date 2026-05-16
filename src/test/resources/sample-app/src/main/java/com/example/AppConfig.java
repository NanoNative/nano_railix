package com.example;

import org.nanonative.railix.apt.Railix;
import org.nanonative.railix.apt.RailField;

@Railix
public interface AppConfig {
    String input();

    @RailField("custom_output")
    Integer output();
}
