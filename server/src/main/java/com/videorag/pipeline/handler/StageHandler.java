package com.videorag.pipeline.handler;

import com.videorag.pipeline.Stage;

public interface StageHandler {

    Stage stage();

    void handle(Long mediaId);
}
