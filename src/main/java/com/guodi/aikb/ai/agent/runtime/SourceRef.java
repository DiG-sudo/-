package com.guodi.aikb.ai.agent.runtime;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import lombok.EqualsAndHashCode;
import lombok.Getter;

@Getter
@EqualsAndHashCode
public final class SourceRef {

    private final String path;
    private final int startLine;
    private final int endLine;

    @JsonCreator
    public SourceRef(
            @JsonProperty("path") String path,
            @JsonProperty("startLine") int startLine,
            @JsonProperty("endLine") int endLine) {
        if (path == null || path.isBlank()
                || startLine < 1 || endLine < startLine) {
            throw new IllegalArgumentException("SourceRef范围无效");
        }
        this.path = path;
        this.startLine = startLine;
        this.endLine = endLine;
    }
}
