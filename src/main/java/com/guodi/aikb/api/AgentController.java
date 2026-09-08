package com.guodi.aikb.api;

import java.util.concurrent.atomic.AtomicLong;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.guodi.aikb.ai.agent.AgentResult;
import com.guodi.aikb.ai.agent.AgentService;

@RestController
@RequestMapping("/api/agent")
public class AgentController {

    private final AtomicLong sessionIds = new AtomicLong(System.currentTimeMillis());
    private final AgentService agentService;

    public AgentController(AgentService agentService) {
        this.agentService = agentService;
    }

    @PostMapping("/analyze")
    public AgentResponse analyze(@RequestBody AgentRequest request) {
        if (request == null || request.question() == null || request.question().isBlank()) {
            throw new IllegalArgumentException("question不能为空");
        }
        Long sessionId = request.sessionId() == null
                ? sessionIds.incrementAndGet()
                : request.sessionId();
        AgentResult result = agentService.chat(
                request.question(),
                sessionId,
                Boolean.TRUE.equals(request.verified())
        );
        return AgentResponse.from(sessionId, result);
    }
}
