package com.guodi.aikb.ai.tool.catalog;

import lombok.Data;

@Data
public class ToolDescriptor {
    private String name;
    private String description;
    private String kind;
    private String server;
    private String status;
    private boolean enabled;
}
