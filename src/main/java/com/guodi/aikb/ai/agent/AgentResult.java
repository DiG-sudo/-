package com.guodi.aikb.ai.agent;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.guodi.aikb.ai.agent.runtime.AgentEvidence;
import com.guodi.aikb.ai.agent.runtime.SourceRef;

import lombok.Getter;

@Getter
public class AgentResult {

    private final String answer;
    private final String verificationStatus;
    private final String verificationReason;
    private final List<AgentEvidence> evidence;
    private final List<SourceRef> sources;

    public AgentResult(
            String answer,
            String verificationStatus,
            String verificationReason,
            List<AgentEvidence> evidence) {

        this.answer = answer;
        this.verificationStatus = verificationStatus;
        this.verificationReason = verificationReason;
        this.evidence = evidence == null ? List.of() : List.copyOf(evidence);
        Map<String, List<SourceRef>> sourcesByPath = new LinkedHashMap<>();
        for (AgentEvidence item : this.evidence) {
            if (item != null && item.isSuccessful()) {
                for (SourceRef source : item.getSourceRefs()) {
                    sourcesByPath.computeIfAbsent(source.getPath(), ignored -> new ArrayList<>())
                            .add(source);
                }
            }
        }
        this.sources = mergeSources(sourcesByPath);
    }

    private List<SourceRef> mergeSources(Map<String, List<SourceRef>> sourcesByPath) {
        List<SourceRef> merged = new ArrayList<>();
        for (Map.Entry<String, List<SourceRef>> entry : sourcesByPath.entrySet()) {
            List<SourceRef> ranges = entry.getValue().stream()
                    .sorted(Comparator.comparingInt(SourceRef::getStartLine)
                            .thenComparingInt(SourceRef::getEndLine))
                    .toList();
            int start = ranges.getFirst().getStartLine();
            int end = ranges.getFirst().getEndLine();
            for (int index = 1; index < ranges.size(); index++) {
                SourceRef current = ranges.get(index);
                if (current.getStartLine() <= end + 1) {
                    end = Math.max(end, current.getEndLine());
                    continue;
                }
                merged.add(new SourceRef(entry.getKey(), start, end));
                start = current.getStartLine();
                end = current.getEndLine();
            }
            merged.add(new SourceRef(entry.getKey(), start, end));
        }
        return List.copyOf(merged);
    }
}
