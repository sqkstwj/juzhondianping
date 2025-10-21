package com.hmdp.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.hmdp.utils.RedisData;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;


/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Slf4j
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;
    
    private static final Random RANDOM = new Random();
    
    /**
     * 线程池：用于逻辑过期方案中异步重建缓存
     * 
     * 为什么需要线程池？
     * - 逻辑过期方案中，重建缓存是异步的，不能阻塞查询线程
     * - 使用固定大小的线程池，避免创建过多线程导致系统资源耗尽
     * - 10个线程足够处理缓存重建任务
     */
    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);
    
    @Override
    public Result queryById(Long id) {
        // 方案1：使用互斥锁解决缓存击穿问题（保证一致性）
        //return queryWithMutex(id);
        
        // 方案2：使用逻辑过期解决缓存击穿问题（保证高可用）
        return queryWithLogicalExpire(id);
    }
    
    /**
     * 使用互斥锁解决缓存击穿问题
     * 
     * 缓存击穿：热点key过期瞬间，大量请求同时查询数据库，导致数据库压力剧增
     * 
     * 解决思路：
     * 1. 缓存未命中时，不是直接查数据库，而是先尝试获取互斥锁
     * 2. 获取锁成功的线程：查询数据库 → 重建缓存 → 释放锁
     * 3. 获取锁失败的线程：等待一段时间后重试（循环查询缓存）
     * 4. 最终所有线程都能从缓存获取数据，只有一个线程访问了数据库
     * 
     * @param id 商铺ID
     * @return 商铺信息
     */
    public Result queryWithMutex(Long id) {
        String key = CACHE_SHOP_KEY + id;
        
        // 1. 从Redis查询商铺缓存
        String shopJson = stringRedisTemplate.opsForValue().get(key);

        // 2. 判断缓存是否命中
        if(StrUtil.isNotBlank(shopJson)) {
            // 3. 命中，直接返回商铺信息
            Shop shop = JSONUtil.toBean(shopJson, Shop.class);
            return Result.ok(shop);
        }
        
        // 4. 判断命中的是否是空值（用于解决缓存穿透）
        // 注意：空字符串 "" 不是 null，StrUtil.isNotBlank会返回false
        if(shopJson != null){
            // 命中的是空值，说明数据库也没有这个商铺
            return Result.fail("店铺信息不存在！");
        }
        
        // 5. 未命中，需要查询数据库重建缓存
        // 5.1 尝试获取互斥锁
        String lockKey = LOCK_SHOP_KEY + id;  // lock:shop:1
        Shop shop = null;
        try {
            boolean isLock = tryLock(lockKey);
            
            // 5.2 判断是否获取锁成功
            if(!isLock) {
                // 5.3 获取锁失败，说明有其他线程正在重建缓存
                // 等待一段时间后，递归重试（重新查询缓存）
                Thread.sleep(50);  // 休眠50ms
                return queryWithMutex(id);  // 递归调用，重新查询缓存
            }
            
            // 5.4 获取锁成功，查询数据库
            // DoubleCheck：再次检查缓存，防止在等待锁的期间，缓存已经被其他线程重建
            shopJson = stringRedisTemplate.opsForValue().get(key);
            if(StrUtil.isNotBlank(shopJson)) {
                // 缓存已存在，直接返回
                shop = JSONUtil.toBean(shopJson, Shop.class);
                return Result.ok(shop);
            }
            
            // 6. 缓存确实不存在，查询数据库
            shop = getById(id);

            // 模拟重建缓存的延迟（方便测试，生产环境删除）
            // Thread.sleep(200);

            // 7. 数据库中也不存在该商铺
            if(shop == null){
                // 将空值写入Redis，防止缓存穿透（恶意攻击不存在的ID）
                long expireTime = CACHE_NULL_TTL + RANDOM.nextInt(2);  // 2~3分钟随机
                stringRedisTemplate.opsForValue().set(key, "", expireTime, TimeUnit.MINUTES);
                return Result.fail("店铺不存在！");
            }

            // 8. 数据库存在，写入Redis缓存
            long expireTime = CACHE_SHOP_TTL + RANDOM.nextInt(10);  // 30~39分钟随机
            stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shop), 
                expireTime, TimeUnit.MINUTES);
                
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            // 9. 释放互斥锁（无论成功失败都要释放）
            unlock(lockKey);
        }

        // 10. 返回商铺信息
        return Result.ok(shop);
    }
    
    /**
     * 尝试获取分布式锁
     * 
     * 原理：利用Redis的SETNX命令（SET if Not eXists）
     * - SETNX key value：当key不存在时设置成功，返回1
     * - 当key已存在时设置失败，返回0
     * 
     * 为什么要设置过期时间？
     * - 防止死锁：如果获取锁的线程崩溃，没有执行unlock，会导致锁永远无法释放
     * - 兜底方案：即使unlock没有执行，10秒后锁也会自动释放
     * 
     * @param key 锁的key
     * @return true-获取锁成功，false-获取锁失败
     */
    private boolean tryLock(String key) {
        // setIfAbsent 就是 SETNX 命令
        // 设置成功返回true，设置失败返回false
        Boolean flag = stringRedisTemplate.opsForValue()
                .setIfAbsent(key, "1", LOCK_SHOP_TTL, TimeUnit.SECONDS);
        // 注意：不要直接返回flag，因为flag可能是null
        // 使用BooleanUtil工具类安全转换（hutool提供）
        return Boolean.TRUE.equals(flag);
    }
    
    /**
     * 释放分布式锁
     * 
     * 原理：删除Redis中的锁key
     * 
     * 注意：这里是简单实现，生产环境需要考虑：
     * 1. 避免误删别人的锁（应该判断value是否是自己的）
     * 2. 使用Lua脚本保证判断和删除的原子性
     * 3. 可以使用Redisson框架，已经实现了完善的分布式锁
     * 
     * @param key 锁的key
     */
    private void unlock(String key) {
        stringRedisTemplate.delete(key);
    }
    
    /**
     * 使用逻辑过期解决缓存击穿问题
     * 
     * 缓存击穿：热点key过期瞬间，大量请求同时查询数据库
     * 
     * 解决思路（逻辑过期）：
     * 1. 缓存永不过期（Redis不设置TTL）
     * 2. 在数据中添加逻辑过期时间字段（expireTime）
     * 3. 查询时判断逻辑过期时间：
     *    - 未过期：直接返回数据
     *    - 已过期：尝试获取互斥锁
     *      - 获取成功：开启独立线程异步重建缓存，当前线程立即返回旧数据
     *      - 获取失败：说明有其他线程在重建，直接返回旧数据
     * 
     * 优点：
     * - 性能极好：查询线程不需要等待，始终能立即返回
     * - 高可用：即使数据库宕机，也能返回旧数据
     * 
     * 缺点：
     * - 短暂数据不一致：缓存更新期间返回的是旧数据
     * - 额外内存：需要存储逻辑过期时间
     * - 实现复杂：需要线程池、额外数据结构
     * 
     * @param id 商铺ID
     * @return 商铺信息
     */
    public Result queryWithLogicalExpire(Long id) {
        String key = CACHE_SHOP_KEY + id;
        
        // 1. 从Redis查询商铺缓存
        String shopJson = stringRedisTemplate.opsForValue().get(key);

        // 2. 判断缓存是否存在
        if(StrUtil.isBlank(shopJson)) {
            // 3. 缓存不存在，降级到互斥锁方案
            log.warn("逻辑过期方案：缓存不存在，降级到互斥锁方案。shopId={}", id);
            return queryWithMutex(id);
        }
        
        log.debug("逻辑过期方案：缓存命中。shopId={}", id);
        
        // 4. 缓存存在，需要判断是否逻辑过期
        // 4.1 将JSON反序列化为对象
        RedisData redisData = JSONUtil.toBean(shopJson, RedisData.class);
        
        // 4.2 防御性检查：如果反序列化失败或数据格式不对，降级到互斥锁方案
        if(redisData == null || redisData.getData() == null) {
            // 数据格式不正确（可能是旧版本缓存），删除并使用互斥锁方案重建
            stringRedisTemplate.delete(key);
            return queryWithMutex(id);
        }
        
        Shop shop = JSONUtil.toBean((JSONObject) redisData.getData(), Shop.class);
        LocalDateTime expireTime = redisData.getExpireTime();
        
        // 5. 判断是否逻辑过期
        // 5.1 如果expireTime为null，说明是旧格式数据，视为已过期需要重建
        if(expireTime != null && expireTime.isAfter(LocalDateTime.now())) {
            // 5.2 未过期，直接返回商铺信息
            log.debug("逻辑过期方案：缓存未过期，直接返回。shopId={}, expireTime={}", id, expireTime);
            return Result.ok(shop);
        }
        
        log.info("逻辑过期方案：缓存已过期，准备重建。shopId={}, expireTime={}", id, expireTime);
        
        // 5.2 已过期，需要重建缓存
        // 6. 尝试获取互斥锁
        String lockKey = LOCK_SHOP_KEY + id;
        boolean isLock = tryLock(lockKey);
        
        // 7. 判断是否获取锁成功
        if(isLock) {
            // 7.1 获取锁成功，开启独立线程重建缓存
            log.info("逻辑过期方案：获取锁成功，开启异步线程重建缓存。shopId={}", id);
            // 注意：这里使用了线程池，避免频繁创建线程
            CACHE_REBUILD_EXECUTOR.submit(() -> {
                try {
                    // 重建缓存
                    // 模拟重建延迟（测试用，生产环境删除）
                    //Thread.sleep(200);
                    
                    // 写入Redis（设置逻辑过期时间为30分钟后）
                    // 注意：saveShopToRedis方法内部会查询数据库
                    this.saveShopToRedis(id, 30L);
                    
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    // 释放锁
                    unlock(lockKey);
                }
            });
        }
        
        // 8. 返回过期的商铺信息（无论是否获取锁成功）
        // 这就是逻辑过期方案的核心：立即返回，不等待
        log.debug("逻辑过期方案：立即返回旧数据。shopId={}", id);
        return Result.ok(shop);
    }
    
    /**
     * 将商铺数据保存到Redis（带逻辑过期时间）
     * 
     * 使用场景：
     * 1. 缓存预热：应用启动时，将热点数据提前加载到Redis
     * 2. 缓存重建：逻辑过期后，异步重建缓存
     * 
     * 数据结构：
     * {
     *   "data": {...商铺信息...},
     *   "expireTime": "2024-01-01T12:00:00"
     * }
     * 
     * @param id 商铺ID
     * @param expireSeconds 逻辑过期时间（秒）
     */
    public void saveShopToRedis(Long id, Long expireSeconds) {
        // 1. 查询商铺数据
        Shop shop = getById(id);
        
        // 2. 封装逻辑过期时间
        RedisData redisData = new RedisData();
        redisData.setData(shop);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(expireSeconds));
        
        // 3. 写入Redis（注意：不设置TTL，永不过期）
        // 使用 JSONUtil 配置，确保 LocalDateTime 正确序列化
        String jsonStr = JSONUtil.toJsonStr(redisData);
        stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id, jsonStr);
        
        log.info("缓存预热完成。shopId={}, expireTime={}", id, redisData.getExpireTime());
    }
    
    @Override
    @Transactional
    public Result update(Shop shop) {
        Long id = shop.getId();
        if(id == null){
            return Result.fail("店铺id不能为空");
        }
        
        // 1.更新数据库
        updateById(shop);
        
        // 2.删除缓存
        stringRedisTemplate.delete(CACHE_SHOP_KEY + id);
        
        return Result.ok();
    }
}
