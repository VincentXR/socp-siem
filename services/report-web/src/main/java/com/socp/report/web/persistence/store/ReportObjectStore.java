package com.socp.report.web.persistence.store;


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
import java.util.Map;

/**
 * MinIO 对象存储（S3 协议）：报表导出/归档。
 *
 * <p>生产环境把报表快照、案件证据、资产附件归档到对象存储（低成本、可审计）；
 * 当前落地 REPORT 链路：日报/趋势 JSON 导出 → 存 MinIO `reports/` 前缀，支持
 * 列表与 7 天有效期的预签名下载链接。MinIO 不可用时静默降级（不影响报表主链路）。
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
        this.bucket = bucket;
        this.enabled = enabled;
        if (!enabled) {
            this.client = null;
            return;
        }
        this.client = MinioClient.builder()
                .endpoint(url)
                .credentials(accessKey, secretKey)
                .build();
        try {
            ensureBucket();
        } catch (Exception e) {
            log.warn("MinIO 初始化失败（静默降级）: {}", e.getMessage());
        }
    }

    private void ensureBucket() throws Exception {
        boolean exists = client.bucketExists(BucketExistsArgs.builder().bucket(bucket).build());
        if (!exists) {
            client.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
            log.info("MinIO 创建 bucket {}", bucket);
        }
    }

    /** 保存对象，返回对象 key；失败返回 null（静默降级）。 */
    public String put(String key, String content, String contentType) {
        if (!enabled || client == null) return null;
        try {
            client.putObject(PutObjectArgs.builder()
                    .bucket(bucket)
                    .object(key)
                    .stream(new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8)),
                            content.getBytes(StandardCharsets.UTF_8).length, -1)
                    .contentType(contentType)
                    .build());
            return key;
        } catch (Exception e) {
            log.warn("MinIO 写入失败 key={} err={}（静默降级）", key, e.getMessage());
            return null;
        }
    }

    /** 列出 bucket 内对象（前缀过滤）。 */
    public List<Map<String, Object>> list(String prefix) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (!enabled || client == null) return out;
        try {
            for (Result<Item> r : client.listObjects(ListObjectsArgs.builder()
                    .bucket(bucket).prefix(prefix).recursive(true).build())) {
                Item item = r.get();
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("key", item.objectName());
                m.put("size", item.size());
                m.put("lastModified", item.lastModified() == null ? null : item.lastModified().toString());
                out.add(m);
            }
        } catch (Exception e) {
            log.warn("MinIO 列表失败 prefix={} err={}（静默降级）", prefix, e.getMessage());
        }
        return out;
    }

    /** 生成 7 天有效的预签名下载 URL。 */
    public String presignedGet(String key) {
        if (!enabled || client == null) return null;
        try {
            return client.getPresignedObjectUrl(GetPresignedObjectUrlArgs.builder()
                    .bucket(bucket).object(key).method(Method.GET)
                    .expiry(60 * 60 * 24 * 7).build());
        } catch (Exception e) {
            log.warn("MinIO 预签名失败 key={} err={}（静默降级）", key, e.getMessage());
            return null;
        }
    }

    /** 删除对象。 */
    public boolean remove(String key) {
        if (!enabled || client == null) return false;
        try {
            client.removeObject(RemoveObjectArgs.builder().bucket(bucket).object(key).build());
            return true;
        } catch (Exception e) {
            log.warn("MinIO 删除失败 key={} err={}（静默降级）", key, e.getMessage());
            return false;
        }
    }

    /** 当前日期 yyyyMMdd，用于对象 key 前缀。 */
    public static String today() {
        return ZonedDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd"));
    }
}
