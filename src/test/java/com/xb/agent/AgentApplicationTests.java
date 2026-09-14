package com.xb.agent;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/** 上下文加载测试 */
@SpringBootTest(properties = {
        "langchain4j.open-ai.api-key=test-key",
        "langchain4j.open-ai.base-url=http://localhost:9999/v1"
})
@ActiveProfiles("test")
class AgentApplicationTests {

    @Test
    void contextLoads() {
    }
}
