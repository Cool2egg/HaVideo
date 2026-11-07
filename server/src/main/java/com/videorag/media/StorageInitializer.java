package com.videorag.media;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Component
public class StorageInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(StorageInitializer.class);

    private final StorageService storageService;

    public StorageInitializer(StorageService storageService) {
        this.storageService = storageService;
    }

    @Override
    public void run(ApplicationArguments args) {
        storageService.ensureBucket();
        log.info("storage_initialized");
    }
}
