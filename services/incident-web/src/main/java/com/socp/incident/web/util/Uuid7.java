package com.socp.incident.web.util;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.UUID;

/**
 * UUIDv7 生成器（RFC 9562）。
 *
 * <p>案件主键原本是 {@code "CASE-" + epochMilli}，同一毫秒内并发建案会算出相同主键，
 * 插入即撞 {@code PRIMARY KEY} 失败——这正是"案件毫秒级碰撞"缺陷的根因。UUIDv7 把 48 位毫秒时间戳
 * 放在高位、低位塞满随机位，既带时间序（便于按创建时间排序/索引），又几乎不可能碰撞，
 * 彻底消除主键冲突。实现不依赖任何三方库，纯 JDK。
 */
public final class Uuid7 {

    private static final SecureRandom RND = new SecureRandom();

    private Uuid7() {
    }

    /** 生成一个新的 UUIDv7 字符串（标准 {@code 8-4-4-4-12} 形式）。 */
    public static String next() {
        long ts = Instant.now().toEpochMilli();
        byte[] r = new byte[10];
        RND.nextBytes(r);

        // 高 64 位：48 位时间戳 | 4 位版本(0x7) | 12 位 rand_a
        long msb = (ts << 16)
                | (0x7L << 12)
                | (((r[0] & 0xFFL) << 4) | ((r[1] >> 4) & 0x0FL));

        // 低 64 位：2 位变体(0b10) | 62 位 rand_b
        long lsb = (0x2L << 62)
                | (((r[1] & 0x0FL)) << 58)
                | ((long) (r[2] & 0xFF) << 50)
                | ((long) (r[3] & 0xFF) << 42)
                | ((long) (r[4] & 0xFF) << 34)
                | ((long) (r[5] & 0xFF) << 26)
                | ((long) (r[6] & 0xFF) << 18)
                | ((long) (r[7] & 0xFF) << 10)
                | ((long) (r[8] & 0xFF) << 2)
                | ((long) (r[9] & 0x03));

        return new UUID(msb, lsb).toString();
    }
}
