package com.hmdp.utils;

import cn.hutool.core.lang.UUID;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.util.Collections;
import java.util.concurrent.TimeUnit;

/**
 * 基于Redis + Lua脚本的分布式锁实现（改进版）
 * 
 * 改进点：
 * 1. 使用Lua脚本保证释放锁的原子性
 *    - 之前的版本：get + delete 不是原子操作，可能误删
 *    - 改进后：Lua脚本在Redis服务器端执行，保证原子性
 * 
 * Lua脚本内容：
 * ```lua
 * -- 比较线程标识与锁标识是否一致
 * if (redis.call('get', KEYS[1]) == ARGV[1]) then
 *     -- 一致则删除锁
 *     return redis.call('del', KEYS[1])
 * end
 * return 0
 * ```
 * 
 * 仍然存在的局限：
 * - 不可重入
 * - 无重试机制
 * - 无看门狗机制（锁续期）
 * - 主从一致性问题（Redis主从复制延迟导致锁失效）
 * 
 * 生产环境推荐：
 * - 使用 Redisson，它解决了所有这些问题
 * 
 * @author sqkstwj
 * @since 2025-11-03
 */
public class SimpleRedisLockV2 implements ILock {
    
    private String name;
    private StringRedisTemplate stringRedisTemplate;
    
    private static final String KEY_PREFIX = "lock:";
    private static final String ID_PREFIX = UUID.randomUUID().toString(true) + "-";
    
    /**
     * Lua脚本：释放锁
     * 从类路径加载 unlock.lua 文件
     */
    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT;
    
    static {
        UNLOCK_SCRIPT = new DefaultRedisScript<>();
        UNLOCK_SCRIPT.setLocation(new ClassPathResource("unlock.lua"));
        UNLOCK_SCRIPT.setResultType(Long.class);
    }
    
    public SimpleRedisLockV2(String name, StringRedisTemplate stringRedisTemplate) {
        this.name = name;
        this.stringRedisTemplate = stringRedisTemplate;
    }
    
    @Override
    public boolean tryLock(long timeoutSec) {
        String threadId = ID_PREFIX + Thread.currentThread().getId();
        Boolean success = stringRedisTemplate.opsForValue()
                .setIfAbsent(KEY_PREFIX + name, threadId, timeoutSec, TimeUnit.SECONDS);
        return Boolean.TRUE.equals(success);
    }
    
    /**
     * 释放锁（使用Lua脚本保证原子性）
     */
    @Override
    public void unlock() {
        // 调用Lua脚本
        stringRedisTemplate.execute(
                UNLOCK_SCRIPT,  // Lua脚本
                Collections.singletonList(KEY_PREFIX + name),  // KEYS[1]
                ID_PREFIX + Thread.currentThread().getId()  // ARGV[1]
        );
    }
}

