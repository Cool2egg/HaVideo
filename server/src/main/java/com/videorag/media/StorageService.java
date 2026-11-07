package com.videorag.media;

import com.videorag.config.MinioProperties;
import io.minio.BucketExistsArgs;
import io.minio.ComposeObjectArgs;
import io.minio.ComposeSource;
import io.minio.CopyObjectArgs;
import io.minio.CopySource;
import io.minio.GetObjectArgs;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.ListObjectsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import io.minio.Result;
import io.minio.StatObjectArgs;
import io.minio.StatObjectResponse;
import io.minio.http.Method;
import io.minio.messages.Item;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Service
public class StorageService {

    private static final Logger log = LoggerFactory.getLogger(StorageService.class);

    private final MinioClient client;
    private final MinioProperties properties;

    public StorageService(MinioClient client, MinioProperties properties) {
        this.client = client;
        this.properties = properties;
    }

    public void ensureBucket() {
        try {
            boolean exists = client.bucketExists(BucketExistsArgs.builder()
                    .bucket(properties.getBucket()).build());
            if (!exists) {
                client.makeBucket(MakeBucketArgs.builder()
                        .bucket(properties.getBucket()).build());
                log.info("minio_bucket_created bucket={}", properties.getBucket());
            }
        } catch (Exception error) {
            log.warn("minio_bucket_check_failed bucket={}", properties.getBucket(), error);
        }
    }

    public void put(String objectKey, InputStream input, long size, String contentType) {
        try {
            client.putObject(PutObjectArgs.builder()
                    .bucket(properties.getBucket())
                    .object(objectKey)
                    .stream(input, size, -1)
                    .contentType(contentType)
                    .build());
        } catch (Exception error) {
            throw new IllegalStateException("对象存储写入失败: " + objectKey, error);
        }
    }

    public void compose(String targetKey, List<String> sourceKeys) {
        try {
            List<ComposeSource> sources = sourceKeys.stream()
                    .map(key -> ComposeSource.builder()
                            .bucket(properties.getBucket())
                            .object(key)
                            .build())
                    .toList();
            client.composeObject(ComposeObjectArgs.builder()
                    .bucket(properties.getBucket())
                    .object(targetKey)
                    .sources(sources)
                    .build());
        } catch (Exception error) {
            throw new IllegalStateException("对象存储合并失败: " + targetKey, error);
        }
    }

    public void copy(String sourceKey, String targetKey) {
        try {
            client.copyObject(CopyObjectArgs.builder()
                    .bucket(properties.getBucket())
                    .object(targetKey)
                    .source(CopySource.builder()
                            .bucket(properties.getBucket())
                            .object(sourceKey)
                            .build())
                    .build());
        } catch (Exception error) {
            throw new IllegalStateException("对象存储复制失败: " + sourceKey, error);
        }
    }

    public String presignedGet(String objectKey, Duration ttl) {
        try {
            return client.getPresignedObjectUrl(GetPresignedObjectUrlArgs.builder()
                    .method(Method.GET)
                    .bucket(properties.getBucket())
                    .object(objectKey)
                    .expiry((int) ttl.toSeconds(), TimeUnit.SECONDS)
                    .build());
        } catch (Exception error) {
            throw new IllegalStateException("生成播放地址失败", error);
        }
    }

    public InputStream get(String objectKey) {
        try {
            return client.getObject(GetObjectArgs.builder()
                    .bucket(properties.getBucket())
                    .object(objectKey)
                    .build());
        } catch (Exception error) {
            throw new IllegalStateException("对象读取失败: " + objectKey, error);
        }
    }

    public long size(String objectKey) {
        try {
            StatObjectResponse stat = client.statObject(StatObjectArgs.builder()
                    .bucket(properties.getBucket())
                    .object(objectKey)
                    .build());
            return stat.size();
        } catch (Exception error) {
            throw new IllegalStateException("对象不存在: " + objectKey, error);
        }
    }

    public void remove(String objectKey) {
        try {
            client.removeObject(RemoveObjectArgs.builder()
                    .bucket(properties.getBucket())
                    .object(objectKey)
                    .build());
        } catch (Exception error) {
            log.warn("minio_remove_failed object={}", objectKey, error);
        }
    }

    public void removePrefix(String prefix) {
        try {
            Iterable<Result<Item>> items = client.listObjects(ListObjectsArgs.builder()
                    .bucket(properties.getBucket())
                    .prefix(prefix)
                    .recursive(true)
                    .build());
            for (Result<Item> item : items) {
                remove(item.get().objectName());
            }
        } catch (Exception error) {
            log.warn("minio_remove_prefix_failed prefix={}", prefix, error);
        }
    }
}
