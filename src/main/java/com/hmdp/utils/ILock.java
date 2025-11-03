package com.hmdp.utils;

/**
 * 分布式锁接口
 * 
 * @author sqkstwj
 * @since 2025-11-03
 */
public interface ILock {
    
    /**
     * 尝试获取锁
     * 
     * @param timeoutSec 锁的超时时间（秒），过期后自动释放
     * @return true表示获取锁成功，false表示获取锁失败
     */
    boolean tryLock(long timeoutSec);
    
    /**
     * 释放锁
     */
    void unlock();
}

