package com.wgblackmon.harness;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Map;

public interface Tool {

    String name();

    String description();

    Map<String, Object> inputSchema();

    String execute(JsonNode input) throws Exception;
}
