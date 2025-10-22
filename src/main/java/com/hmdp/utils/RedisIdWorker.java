package com.hmdp.utils;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * 全局唯一ID生成器
 * 
 * 为什么需要全局唯一ID？
 * 1. 数据库自增ID在分库分表场景下会重复
 * 2. 暴露数据量信息（从订单ID可以推算出每天订单量）
 * 3. 需要保证唯一性、高可用、高性能、递增性
 * 
 * ID组成（64位Long）：
 * ┌─────────────────────────────────────────────────────────┐
 * │ 1位符号位 | 31位时间戳 | 32位序列号                      │
 * │   0      |  秒级时间  | 日期(5位) + 自增序列(27位)        │
 * └─────────────────────────────────────────────────────────┘
 * 
 * 时间戳：从2022年1月1日开始的秒数（31位可用69年）
 * 序列号：当天的第几个ID（32位可达42亿）
 * 
 * @author sqkstwj
 * @since 2024-10-20
 */
@Component
public class RedisIdWorker {
    
    /**
     * 开始时间戳（2022-01-01 00:00:00）
     * 用于计算时间差，节省位数
     */
    private static final long BEGIN_TIMESTAMP = 1640995200L;
    
    /**
     * 序列号的位数（32位）
     */
    private static final int COUNT_BITS = 32;
    
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    
    /**
     * 生成全局唯一ID
     * 
     * 实现思路：
     * 1. 获取当前时间戳（从2022-01-01开始的秒数）
     * 2. 获取当天的自增序列号（Redis INCR命令保证原子性）
     * 3. 拼接时间戳和序列号，返回64位Long
     * 
     * 为什么使用Redis INCR？
     * - 原子性操作，天然支持高并发
     * - 按天分key，避免单个key过大
     * - 设置过期时间，自动清理
     * 
     * @param keyPrefix key前缀（如"order"、"user"）
     * @return 全局唯一ID
     */
    public long nextId(String keyPrefix) {
        // 1. 生成时间戳（当前时间 - 开始时间）
        LocalDateTime now = LocalDateTime.now();
        long nowSecond = now.toEpochSecond(ZoneOffset.UTC);
        long timestamp = nowSecond - BEGIN_TIMESTAMP;
        
        // 2. 生成序列号
        // 2.1 获取当前日期，精确到天（用于按天分key）
        // 例如：2024-10-20 → "20241020"
        String date = now.format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        
        // 2.2 使用Redis自增，key格式：icr:keyPrefix:date
        // 例如：icr:order:20241020
        // 这样每天都是一个新的key，避免单个key数值过大
        long count = stringRedisTemplate.opsForValue()
                .increment("icr:" + keyPrefix + ":" + date);
        
        // 3. 拼接并返回（位运算）
        // 时间戳左移32位（腾出序列号的位置）
        // 然后与序列号进行或运算，拼接成完整ID
        // 
        // 例如：
        // timestamp = 100000000 (二进制：...0101111101011110000100000000)
        // 左移32位：     ...010111110101111000010000000000000000000000000000000000000000
        // count = 1:     ...000000000000000000000000000000000000000000000000000000000001
        // 或运算结果：    ...010111110101111000010000000000000000000000000000000000000001
        return timestamp << COUNT_BITS | count;
    }
}
