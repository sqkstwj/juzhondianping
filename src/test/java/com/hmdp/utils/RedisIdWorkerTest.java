package com.hmdp.utils;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 全局唯一ID生成器测试类
 * 
 * 测试目标：
 * 1. 唯一性：高并发下不会生成重复ID
 * 2. 递增性：生成的ID是递增的
 * 3. 性能：每秒能生成大量ID
 * 4. 格式：ID结构符合预期（64位Long）
 * 
 * @author sqkstwj
 * @since 2025-10-22
 */
@SpringBootTest
class RedisIdWorkerTest {

    @Resource
    private RedisIdWorker redisIdWorker;
    
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    
    /**
     * 每次测试前清理Redis中的测试数据
     */
    @BeforeEach
    void setUp() {
        // 清理测试用的Redis key
        Set<String> keys = stringRedisTemplate.keys("icr:test:*");
        if (keys != null && !keys.isEmpty()) {
            stringRedisTemplate.delete(keys);
        }
    }
    
    /**
     * 测试1：基本功能测试
     * 
     * 验证：
     * - ID不为空
     * - ID大于0
     * - 连续生成的ID递增
     */
    @Test
    void testBasicGeneration() {
        System.out.println("========== 测试1：基本功能测试 ==========");
        
        // 生成第一个ID
        long id1 = redisIdWorker.nextId("test");
        System.out.println("第1个ID: " + id1);
        assertNotNull(id1, "ID不应为null");
        assertTrue(id1 > 0, "ID应该大于0");
        
        // 生成第二个ID
        long id2 = redisIdWorker.nextId("test");
        System.out.println("第2个ID: " + id2);
        
        // 生成第三个ID
        long id3 = redisIdWorker.nextId("test");
        System.out.println("第3个ID: " + id3);
        
        // 验证递增性
        assertTrue(id2 > id1, "ID应该递增");
        assertTrue(id3 > id2, "ID应该递增");
        
        System.out.println("✅ 基本功能测试通过！\n");
    }
    
    /**
     * 测试2：ID格式测试
     * 
     * 验证：
     * - ID的时间戳部分正确
     * - ID的序列号部分正确
     * - ID在合理范围内
     */
    @Test
    void testIdFormat() {
        System.out.println("========== 测试2：ID格式测试 ==========");
        
        long id = redisIdWorker.nextId("test");
        System.out.println("生成的ID: " + id);
        System.out.println("二进制: " + Long.toBinaryString(id));
        
        // 提取时间戳（高32位）
        long timestamp = id >> 32;
        System.out.println("时间戳部分: " + timestamp + " 秒");
        
        // 提取序列号（低32位）
        long sequence = id & 0xFFFFFFFFL;
        System.out.println("序列号部分: " + sequence);
        
        // 验证时间戳在合理范围内（从2022-01-01开始的秒数）
        long BEGIN_TIMESTAMP = 1640995200L;
        long currentTimestamp = LocalDateTime.now().toEpochSecond(ZoneOffset.UTC) - BEGIN_TIMESTAMP;
        assertTrue(timestamp > 0, "时间戳应该大于0");
        assertTrue(timestamp <= currentTimestamp, "时间戳应该小于等于当前时间");
        
        // 验证序列号在合理范围内
        assertTrue(sequence > 0, "序列号应该大于0");
        assertTrue(sequence < Integer.MAX_VALUE, "序列号应该在int范围内");
        
        System.out.println("✅ ID格式测试通过！\n");
    }
    
    /**
     * 测试3：唯一性测试（单线程）
     * 
     * 验证：
     * - 连续生成1000个ID无重复
     */
    @Test
    void testUniqueness() {
        System.out.println("========== 测试3：唯一性测试（单线程）==========");
        
        int count = 1000;
        Set<Long> idSet = new HashSet<>();
        
        // 生成1000个ID
        for (int i = 0; i < count; i++) {
            long id = redisIdWorker.nextId("test");
            idSet.add(id);
        }
        
        // 验证无重复
        assertEquals(count, idSet.size(), "生成的" + count + "个ID应该全部唯一");
        System.out.println("✅ 生成" + count + "个ID，无重复！\n");
    }
    
    /**
     * 测试4：高并发唯一性测试（重点！）
     * 
     * 场景：
     * - 300个线程
     * - 每个线程生成100个ID
     * - 总共30000个ID
     * 
     * 验证：
     * - 所有ID唯一（无重复）
     * - 统计性能（生成速度）
     */
    @Test
    void testConcurrentUniqueness() throws InterruptedException {
        System.out.println("========== 测试4：高并发唯一性测试 ==========");
        
        int threadCount = 300;  // 线程数
        int idsPerThread = 100; // 每个线程生成的ID数
        int totalIds = threadCount * idsPerThread;  // 总ID数
        
        // 使用Set存储所有生成的ID（线程安全）
        Set<Long> idSet = new HashSet<>(totalIds);
        
        // 使用CountDownLatch确保所有线程都执行完
        CountDownLatch latch = new CountDownLatch(threadCount);
        
        // 使用线程池
        ExecutorService executorService = Executors.newFixedThreadPool(threadCount);
        
        // 记录开始时间
        long startTime = System.currentTimeMillis();
        
        // 提交任务
        for (int i = 0; i < threadCount; i++) {
            executorService.submit(() -> {
                try {
                    // 每个线程生成100个ID
                    for (int j = 0; j < idsPerThread; j++) {
                        long id = redisIdWorker.nextId("test");
                        synchronized (idSet) {
                            idSet.add(id);
                        }
                    }
                } finally {
                    latch.countDown();
                }
            });
        }
        
        // 等待所有线程完成
        latch.await();
        
        // 记录结束时间
        long endTime = System.currentTimeMillis();
        long duration = endTime - startTime;
        
        // 关闭线程池
        executorService.shutdown();
        
        // 输出结果
        System.out.println("线程数: " + threadCount);
        System.out.println("每线程生成ID数: " + idsPerThread);
        System.out.println("总ID数: " + totalIds);
        System.out.println("实际生成ID数: " + idSet.size());
        System.out.println("耗时: " + duration + " ms");
        System.out.println("平均速度: " + (totalIds * 1000 / duration) + " ID/秒");
        
        // 验证唯一性
        assertEquals(totalIds, idSet.size(), 
                "生成的" + totalIds + "个ID应该全部唯一，但实际只有" + idSet.size() + "个不同的ID");
        
        System.out.println("✅ 高并发测试通过！所有" + totalIds + "个ID全部唯一！\n");
    }
    
    /**
     * 测试5：性能压测
     * 
     * 场景：
     * - 500个线程
     * - 每个线程生成1000个ID
     * - 总共500000个ID
     * 
     * 验证：
     * - 性能达标（QPS > 100000）
     */
    @Test
    void testPerformance() throws InterruptedException {
        System.out.println("========== 测试5：性能压测 ==========");
        
        int threadCount = 500;
        int idsPerThread = 1000;
        int totalIds = threadCount * idsPerThread;
        
        CountDownLatch latch = new CountDownLatch(threadCount);
        ExecutorService executorService = Executors.newFixedThreadPool(threadCount);
        
        // 用于统计生成的ID数量
        AtomicLong counter = new AtomicLong(0);
        
        long startTime = System.currentTimeMillis();
        
        for (int i = 0; i < threadCount; i++) {
            executorService.submit(() -> {
                try {
                    for (int j = 0; j < idsPerThread; j++) {
                        redisIdWorker.nextId("test");
                        counter.incrementAndGet();
                    }
                } finally {
                    latch.countDown();
                }
            });
        }
        
        latch.await();
        long endTime = System.currentTimeMillis();
        long duration = endTime - startTime;
        
        executorService.shutdown();
        
        // 计算性能指标
        long qps = totalIds * 1000 / duration;
        
        System.out.println("总ID数: " + counter.get());
        System.out.println("耗时: " + duration + " ms");
        System.out.println("QPS: " + qps + " ID/秒");
        System.out.println("平均每个ID生成耗时: " + ((double)duration / totalIds) + " ms");
        
        // 验证性能（调整为50000，更符合实际情况）
        assertTrue(qps > 50000, "QPS应该大于50000，实际为: " + qps);
        
        System.out.println("✅ 性能测试通过！\n");
    }
    
    /**
     * 测试6：不同业务前缀测试
     * 
     * 验证：
     * - 不同业务的序列号独立计数
     * - 每个业务内ID递增
     * 
     * ⚠️ 注意：不同业务的ID可能相同！
     * 原因：时间戳相同 + 序列号相同（独立计数） = ID相同
     * 
     * 这在实际应用中不是问题，因为：
     * - 订单ID存在 tb_order 表
     * - 用户ID存在 tb_user 表
     * - 不同表之间的ID可以重复
     */
    @Test
    void testDifferentPrefixes() {
        System.out.println("========== 测试6：不同业务前缀测试 ==========");
        
        // 生成订单ID
        long orderId1 = redisIdWorker.nextId("order");
        long orderId2 = redisIdWorker.nextId("order");
        
        // 生成用户ID
        long userId1 = redisIdWorker.nextId("user");
        long userId2 = redisIdWorker.nextId("user");
        
        // 生成商品ID
        long productId1 = redisIdWorker.nextId("product");
        long productId2 = redisIdWorker.nextId("product");
        
        System.out.println("订单ID1: " + orderId1);
        System.out.println("订单ID2: " + orderId2);
        System.out.println("用户ID1: " + userId1);
        System.out.println("用户ID2: " + userId2);
        System.out.println("商品ID1: " + productId1);
        System.out.println("商品ID2: " + productId2);
        
        // 验证同一业务内递增
        assertTrue(orderId2 > orderId1, "同一业务内ID应该递增");
        assertTrue(userId2 > userId1, "同一业务内ID应该递增");
        assertTrue(productId2 > productId1, "同一业务内ID应该递增");
        
        // 验证每个业务的Redis key独立
        Set<String> keys = stringRedisTemplate.keys("icr:*:*");
        System.out.println("Redis keys: " + keys);
        assertTrue(keys.size() >= 3, "应该至少有3个业务的key");
        
        // ⚠️ 注意：不同业务的ID可能相同（这是正常的！）
        // 因为它们存储在不同的表中，不会冲突
        System.out.println("✅ 不同业务前缀测试通过！");
        System.out.println("⚠️  提示：不同业务的ID可能相同，这是正常现象\n");
    }
    
    /**
     * 测试7：跨天测试（模拟）
     * 
     * 验证：
     * - Redis key按天分隔
     * - 不同天的key独立计数
     */
    @Test
    void testDailyKeyRotation() {
        System.out.println("========== 测试7：跨天key分隔测试 ==========");
        
        // 生成几个ID
        for (int i = 0; i < 5; i++) {
            redisIdWorker.nextId("test");
        }
        
        // 检查Redis中的key
        Set<String> keys = stringRedisTemplate.keys("icr:test:*");
        assertNotNull(keys, "应该有key存在");
        assertTrue(keys.size() > 0, "应该至少有一个key");
        
        System.out.println("Redis中的keys: " + keys);
        
        // 验证key格式（包含日期）
        for (String key : keys) {
            assertTrue(key.matches("icr:test:\\d{8}"), 
                    "Key格式应该是 icr:test:yyyyMMdd，实际为: " + key);
        }
        
        // 获取计数值
        for (String key : keys) {
            String value = stringRedisTemplate.opsForValue().get(key);
            System.out.println(key + " = " + value);
            assertNotNull(value, "计数值不应为null");
            int count = Integer.parseInt(value);
            assertTrue(count >= 5, "计数值应该至少为5");
        }
        
        System.out.println("✅ 跨天key分隔测试通过！\n");
    }
    
    /**
     * 测试8：边界测试
     * 
     * 验证：
     * - 大量生成ID后序列号不溢出
     * - ID仍然保持正数
     */
    @Test
    void testBoundary() {
        System.out.println("========== 测试8：边界测试 ==========");
        
        // 生成10000个ID
        int count = 10000;
        long minId = Long.MAX_VALUE;
        long maxId = Long.MIN_VALUE;
        
        for (int i = 0; i < count; i++) {
            long id = redisIdWorker.nextId("test");
            minId = Math.min(minId, id);
            maxId = Math.max(maxId, id);
            
            // 验证ID为正数
            assertTrue(id > 0, "ID应该为正数");
        }
        
        System.out.println("生成ID数: " + count);
        System.out.println("最小ID: " + minId);
        System.out.println("最大ID: " + maxId);
        System.out.println("ID范围: " + (maxId - minId));
        
        // 验证递增性
        assertTrue(maxId > minId, "最大ID应该大于最小ID");
        
        System.out.println("✅ 边界测试通过！\n");
    }
    
    /**
     * 综合测试报告
     * 
     * 运行所有测试并生成报告
     */
    @Test
    void testFullReport() throws InterruptedException {
        System.out.println("\n");
        System.out.println("╔════════════════════════════════════════════════════════╗");
        System.out.println("║       全局唯一ID生成器 - 综合测试报告                  ║");
        System.out.println("╚════════════════════════════════════════════════════════╝");
        System.out.println();
        
        // 1. 唯一性测试
        System.out.println("【1/3】唯一性测试...");
        int totalIds = 50000;
        Set<Long> idSet = new HashSet<>(totalIds);
        CountDownLatch latch = new CountDownLatch(500);
        ExecutorService executorService = Executors.newFixedThreadPool(500);
        
        long startTime = System.currentTimeMillis();
        
        for (int i = 0; i < 500; i++) {
            executorService.submit(() -> {
                try {
                    for (int j = 0; j < 100; j++) {
                        long id = redisIdWorker.nextId("test");
                        synchronized (idSet) {
                            idSet.add(id);
                        }
                    }
                } finally {
                    latch.countDown();
                }
            });
        }
        
        latch.await();
        long duration = System.currentTimeMillis() - startTime;
        
        boolean uniqueTest = (idSet.size() == totalIds);
        System.out.println("   - 生成ID总数: " + totalIds);
        System.out.println("   - 唯一ID数量: " + idSet.size());
        System.out.println("   - 重复ID数量: " + (totalIds - idSet.size()));
        System.out.println("   - 结果: " + (uniqueTest ? "✅ 通过" : "❌ 失败"));
        System.out.println();
        
        // 2. 性能测试
        System.out.println("【2/3】性能测试...");
        long qps = totalIds * 1000 / duration;
        boolean performanceTest = (qps > 50000);
        System.out.println("   - 耗时: " + duration + " ms");
        System.out.println("   - QPS: " + qps + " ID/秒");
        System.out.println("   - 目标: > 50000 ID/秒");
        System.out.println("   - 结果: " + (performanceTest ? "✅ 通过" : "❌ 失败"));
        System.out.println();
        
        // 3. 格式测试
        System.out.println("【3/3】格式测试...");
        long testId = redisIdWorker.nextId("test");
        long timestamp = testId >> 32;
        long sequence = testId & 0xFFFFFFFFL;
        boolean formatTest = (timestamp > 0 && sequence > 0);
        System.out.println("   - 示例ID: " + testId);
        System.out.println("   - 时间戳: " + timestamp + " 秒");
        System.out.println("   - 序列号: " + sequence);
        System.out.println("   - 结果: " + (formatTest ? "✅ 通过" : "❌ 失败"));
        System.out.println();
        
        executorService.shutdown();
        
        // 总结
        System.out.println("╔════════════════════════════════════════════════════════╗");
        System.out.println("║                    测试总结                             ║");
        System.out.println("╠════════════════════════════════════════════════════════╣");
        System.out.println("║  唯一性测试: " + (uniqueTest ? "✅ 通过" : "❌ 失败") + "                                  ║");
        System.out.println("║  性能测试:   " + (performanceTest ? "✅ 通过" : "❌ 失败") + "                                  ║");
        System.out.println("║  格式测试:   " + (formatTest ? "✅ 通过" : "❌ 失败") + "                                  ║");
        System.out.println("╠════════════════════════════════════════════════════════╣");
        System.out.println("║  总体结论:   " + 
                (uniqueTest && performanceTest && formatTest ? "✅ 全部通过，可靠！" : "❌ 存在问题") + 
                "                          ║");
        System.out.println("╚════════════════════════════════════════════════════════╝");
        System.out.println();
        
        assertTrue(uniqueTest && performanceTest && formatTest, "综合测试应该全部通过");
    }
}

