-- ai-assistant 知识库 QA 持久化（关键词 → 建议，重启不丢）
CREATE TABLE IF NOT EXISTS t_qa (
    keyword VARCHAR(128) PRIMARY KEY,
    answer  VARCHAR(4096) NOT NULL
);
