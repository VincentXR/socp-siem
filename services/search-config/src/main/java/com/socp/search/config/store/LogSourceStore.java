package com.socp.search.config.store;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.socp.search.config.domain.LogSource;
import com.socp.search.config.domain.ParseFormat;
import com.socp.search.config.domain.SourceType;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 日志源存储——本地切片用 H2 文件库（重启不丢）；生产由独立 search 库 PG 承载。
 * 对外公共 API（save/get/list/enabled/delete）保持不变，渲染器与控制器无需改动。
 */
@Component
public class LogSourceStore {

    private final LogSourceRepository repo;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    public LogSourceStore(LogSourceRepository repo) {
        this.repo = repo;
    }

    public synchronized LogSource save(LogSource src) {
        repo.save(toEntity(src));
        return src;
    }

    public Optional<LogSource> get(String id) {
        return repo.findById(id).map(LogSourceStore::fromEntity);
    }

    public List<LogSource> list() {
        List<LogSource> out = new ArrayList<>();
        for (LogSourceEntity e : repo.findAll()) out.add(fromEntity(e));
        return out;
    }

    public List<LogSource> enabled() {
        return list().stream().filter(LogSource::enabled).toList();
    }

    public synchronized boolean delete(String id) {
        if (repo.existsById(id)) {
            repo.deleteById(id);
            return true;
        }
        return false;
    }

    // ---- 互转 ----

    static LogSourceEntity toEntity(LogSource s) {
        LogSourceEntity e = new LogSourceEntity();
        e.setId(s.id());
        e.setName(s.name());
        e.setType(s.type() == null ? null : s.type().name());
        e.setFormat(s.format() == null ? null : s.format().name());
        e.setPath(s.path());
        e.setAddress(s.address());
        e.setTopic(s.topic());
        e.setEnv(s.env());
        e.setEnabled(s.enabled());
        e.setReadFrom(s.readFrom());
        e.setMultiline(s.multiline());
        e.setSinkTargetId(s.sinkTargetId());
        e.setParseRuleIdsJson(writeJson(s.parseRuleIds()));
        e.setDescription(s.description());
        e.setProtocol(s.protocol());
        e.setCharset(s.charset());
        e.setTimeField(s.timeField());
        e.setTimezone(s.timezone());
        e.setTagsJson(writeJson(s.tags()));
        e.setFrequency(s.frequency());
        e.setCategoryId(s.categoryId());
        e.setGroupId(s.groupId());
        e.setCreatedAt(s.createdAt());
        return e;
    }

    static LogSource fromEntity(LogSourceEntity e) {
        List<String> parseRuleIds = readList(e.getParseRuleIdsJson());
        List<String> tags = readList(e.getTagsJson());
        return new LogSource(e.getId(), e.getName(),
                e.getType() == null ? null : SourceType.valueOf(e.getType()),
                e.getFormat() == null ? null : ParseFormat.valueOf(e.getFormat()),
                e.getPath(), e.getAddress(), e.getTopic(), e.getEnv(), e.isEnabled(),
                e.getReadFrom(), e.getMultiline(), e.getSinkTargetId(),
                parseRuleIds == null ? List.of() : parseRuleIds, e.getDescription(),
                e.getProtocol(), e.getCharset(), e.getTimeField(), e.getTimezone(),
                tags == null ? List.of() : tags, e.getFrequency(), e.getCategoryId(), e.getGroupId(),
                e.getCreatedAt());
    }

    private static String writeJson(Object o) {
        try {
            return MAPPER.writeValueAsString(o);
        } catch (Exception ex) {
            return "[]";
        }
    }

    private static List<String> readList(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            return MAPPER.readValue(json, new TypeReference<>() {
            });
        } catch (Exception ex) {
            return null;
        }
    }
}
