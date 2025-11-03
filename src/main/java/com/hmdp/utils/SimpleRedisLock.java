package com.hmdp.utils;

import cn.hutool.core.lang.UUID;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.concurrent.TimeUnit;

/**
 * 基于Redis的简单分布式锁实现
 * 
 * 实现原理：
 * 1. 获取锁：使用 SETNX 命令（SET if Not eXists）
 *    - SETNX key value：如果key不存在则设置成功返回1，存在则返回0
 *    - 同时设置过期时间，防止死锁
 * 
 * 2. 释放锁：删除key
 *    - 必须判断是不是自己的锁（通过value标识）
 *    - 防止误删其他线程的锁
 * 
 * 使用场景：
 * - 适用于简单的分布式锁场景
 * - 学习分布式锁原理
 * 
 * 局限性：
 * - 不可重入（同一个线程无法多次获取同一把锁）
 * - 无重试机制（获取失败直接返回）
 * - 无看门狗机制（锁过期无法自动续期）
 * 
 * 生产环境推荐：
 * - 使用 Redisson 框架，功能更完善
 * 
 * @author sqkstwj
 * @since 2025-11-03
 */
public class SimpleRedisLock implements ILock {
    
    /**
     * 锁的名称（业务名称）
     */
    private String name;
    
    /**
     * Redis客户端
     */
    private StringRedisTemplate stringRedisTemplate;
    
    /**
     * 锁的key前缀
     */
    private static final String KEY_PREFIX = "lock:";
    
    /**
     * 锁的value前缀（UUID + 线程ID）
     * 作用：防止误删其他线程的锁
     * 
     * 为什么需要线程标识？
     * 假设不加标识：
     * 1. 线程A获取锁，执行业务（耗时长）
     * 2. 锁过期自动释放（超时时间到了）
     * 3. 线程B获取到同一把锁
     * 4. 线程A执行完毕，释放锁 → 误删了线程B的锁！❌
     * 
     * 加上线程标识后：
     * - 每个线程的锁value不同（UUID + 线程ID）
     * - 释放锁时判断value是否匹配，不匹配则不删除
     */
    private static final String ID_PREFIX = UUID.randomUUID().toString(true) + "-";
    
    public SimpleRedisLock(String name, StringRedisTemplate stringRedisTemplate) {
        this.name = name;
        this.stringRedisTemplate = stringRedisTemplate;
    }
    
    /**
     * 尝试获取锁
     * 
     * @param timeoutSec 锁的超时时间（秒）
     * @return true表示获取成功，false表示获取失败
     */
    @Override
    public boolean tryLock(long timeoutSec) {
        // 获取线程标识
        String threadId = ID_PREFIX + Thread.currentThread().getId();
        
        // 获取锁
        // SETNX key value EX seconds
        // 这个操作是原子性的（Redis 2.6.12+）
        Boolean success = stringRedisTemplate.opsForValue()
                .setIfAbsent(KEY_PREFIX + name, threadId, timeoutSec, TimeUnit.SECONDS);
        
        // 注意：不要直接返回 success，因为它是 Boolean 对象，可能是 null
        // 自动拆箱可能会导致空指针异常
        return Boolean.TRUE.equals(success);
    }
    
    /**
     * 释放锁
     * 
     * 注意：这个实现有问题！
     * 
     * 问题场景：
     * 1. 线程A执行 get 命令，获取到自己的锁标识
     * 2. 此时线程A阻塞（GC、网络延迟等）
     * 3. 锁过期，自动释放
     * 4. 线程B获取到同一把锁
     * 5. 线程A恢复，执行 del 命令 → 误删了线程B的锁！
     * 
     * 原因：get 和 del 不是原子操作
     * 
     * 解决方案：
     * - 使用 Lua 脚本保证原子性（见 SimpleRedisLockV2）
     * - 或使用 Redisson 框架
     */
    @Override
    public void unlock() {
        // 获取线程标识
        String threadId = ID_PREFIX + Thread.currentThread().getId();
        
        // 获取锁中的线程标识
        String id = stringRedisTemplate.opsForValue().get(KEY_PREFIX + name);
        
        // 判断标识是否一致
        if (threadId.equals(id)) {
            // 释放锁
            stringRedisTemplate.delete(KEY_PREFIX + name);
        }
    }
}

