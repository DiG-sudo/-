package com.guodi.aikb;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = {
        "spring.ai.openai.api-key=test-key",
        "spring.ai.mcp.client.enabled=false"
})
class ApplicationContextTest {

    @Test
    void contextLoads() {
    }
}
