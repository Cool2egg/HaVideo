package com.videorag.analysis;

import com.videorag.ai.AiClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class ModeRouter {

    private static final Logger log = LoggerFactory.getLogger(ModeRouter.class);

    private final AiClient aiClient;

    public ModeRouter(AiClient aiClient) {
        this.aiClient = aiClient;
    }

    public AnalysisMode route(String goal) {
        try {
            String system = "你是视频分析模式路由器。只输出 JSON，不要解释。";
            String user = """
                    根据用户的分析目标，选择最合适的模式：
                    GENERAL（通用理解）、LEARNING（学习）、REVIEW（审查）、CREATION（创作）。
                    目标：%s
                    输出 JSON：{"mode":"GENERAL|LEARNING|REVIEW|CREATION"}
                    """.formatted(goal);
            String raw = aiClient.chat(system, user, true);
            int start = raw.indexOf('{');
            int end = raw.lastIndexOf('}');
            if (start >= 0 && end > start) {
                String mode = com.alibaba.fastjson2.JSON
                        .parseObject(raw.substring(start, end + 1))
                        .getString("mode");
                return AnalysisMode.fromRequest(mode);
            }
        } catch (RuntimeException error) {
            log.warn("mode_route_failed fallback=GENERAL error={}", error.getMessage());
        }
        return AnalysisMode.GENERAL;
    }
}
