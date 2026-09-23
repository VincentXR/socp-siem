package com.socp.report.web.persistence.store;


import com.socp.platform.error.exception.ApiException;
import io.minio.BucketExistsArgs;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.ListObjectsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import io.minio.Result;
import io.minio.http.Method;
import io.minio.messages.Item;
import com.socp.report.web.config.ReportObjectStorageProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Iterator;
import java.util.Map;

/**
 * Tenant-scoped report JSON storage. Optional storage never blocks report reads,
 * but archive operations fail explicitly when disabled or unavailable.
 */
@Component
public class ReportObjectStore {

    private static final Logger log = LoggerFactory.getLogger(ReportObjectStore.class);

    private final MinioClient client;
    private final String bucket;
    private final boolean enabled;

    @Autowired
    public ReportObjectStore(ReportObjectStorageProperties properties) {
        this(properties.getUrl(), properties.getAccessKey(), properties.getSecretKey(),
                properties.getBucket(), properties.isEnabled());
    }

    public ReportObjectStore(String url, String accessKey, String secretKey, String bucket, boolean enabled) {
        this(enabled ? MinioClient.builder().endpoint(url).credentials(accessKey, secretKey).build()
                : null, bucket);
        if (!enabled) return;
        try {
            ensureBucket();
        } catch (Exception e) {
            log.warn("Report archive bucket initialization failed; archive operations will report failures", e);
        }
    }

    ReportObjectStore(MinioClient client, String bucket) {
        this.client = client;
        this.bucket = bucket;
        this.enabled = client != null;
    }

    private void requireStorage() {
        if (!enabled) throw ApiException.of(503, "Report object storage is disabled");
    }

    private ApiException unavailable(String operation, Exception failure) {
        log.warn("Report object storage {} failed", operation, failure);
        return ApiException.of(503, "Report object storage is unavailable; please retry later");
    }

    private void ensureBucket() throws Exception {
        boolean exists = client.bucketExists(BucketExistsArgs.builder().bucket(bucket).build());
        if (!exists) {
            client.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
            log.info("MinIO 创建 bucket {}", bucket);
        }
    }

    /** Return the key only after the storage write is acknowledged. */
    public String put(String key, String content, String contentType) {
        requireStorage();
        try {
            byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
            client.putObject(PutObjectArgs.builder()
                    .bucket(bucket)
                    .object(key)
                    .stream(new ByteArrayInputStream(bytes), bytes.length, -1)
                    .contentType(contentType)
                    .build());
            return key;
        } catch (Exception e) {
            throw unavailable("write", e);
        }
    }

    /** 列出 bucket 内对象（前缀过滤）。 */
    public List<Map<String, Object>> list(String prefix) {
        return list(prefix, 500);
    }

    /** List at most a bounded number of objects from the tenant prefix. */
    public List<Map<String, Object>> list(String prefix, int limit) {
        List<Map<String, Object>> out = new ArrayList<>();
        int boundedLimit = Math.max(1, Math.min(5_000, limit));
        requireStorage();
        try {
            Iterator<Result<Item>> objects = client.listObjects(ListObjectsArgs.builder()
                    .bucket(bucket).prefix(prefix).recursive(true).build()).iterator();
            // Check the bound before hasNext: the SDK may fetch another page there.
            while (out.size() < boundedLimit && objects.hasNext()) {
                Item item = objects.next().get();
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("key", item.objectName());
                m.put("size", item.size());
                m.put("lastModified", item.lastModified() == null ? null : item.lastModified().toString());
                out.add(m);
            }
        } catch (Exception e) {
            throw unavailable("list", e);
        }
        return out;
    }

    /** 生成 7 天有效的预签名下载 URL。 */
    public String presignedGet(String key) {
        requireStorage();
        try {
            return client.getPresignedObjectUrl(GetPresignedObjectUrlArgs.builder()
                    .bucket(bucket).object(key).method(Method.GET)
                    .expiry(60 * 60 * 24 * 7).build());
        } catch (Exception e) {
            throw unavailable("sign", e);
        }
    }

    /** 删除对象。 */
    public boolean remove(String key) {
        requireStorage();
        try {
            client.removeObject(RemoveObjectArgs.builder().bucket(bucket).object(key).build());
            return true;
        } catch (Exception e) {
            throw unavailable("delete", e);
        }
    }

    /** 当前日期 yyyyMMdd，用于对象 key 前缀。 */
    public static String today() {
        return ZonedDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd"));
    }
}
