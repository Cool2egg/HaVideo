package com.videorag.pipeline.handler;

import com.videorag.pipeline.Stage;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

@Component
public class HandlerRegistry {

    private final Map<Stage, StageHandler> handlers = new EnumMap<>(Stage.class);

    public HandlerRegistry(List<StageHandler> stageHandlers) {
        for (StageHandler handler : stageHandlers) {
            handlers.put(handler.stage(), handler);
        }
    }

    public StageHandler require(Stage stage) {
        StageHandler handler = handlers.get(stage);
        if (handler == null) {
            throw new IllegalStateException("阶段处理器不存在: " + stage);
        }
        return handler;
    }
}
