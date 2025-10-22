package com.hmdp.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.aop.framework.AopContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;

/**
 * <p>
 *  优惠券订单服务实现类
 * </p>
 *
 * @author sqkstwj
 * @since 2025-10-22
 */
@Slf4j
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Resource
    private ISeckillVoucherService seckillVoucherService;
    
    @Resource
    private RedisIdWorker redisIdWorker;
    
    /**
     * 秒杀优惠券
     * 
     * 核心流程：
     * 1. 查询秒杀券信息
     * 2. 判断秒杀时间是否开始/结束
     * 3. 判断库存是否充足
     * 4. 一人一单校验（防止重复购买）
     * 5. 扣减库存（使用乐观锁防止超卖）
     * 6. 创建订单
     * 
     * 关键技术点：
     * - 乐观锁：解决超卖问题
     * - 分布式锁：解决一人一单问题（集群环境下）
     * - 全局唯一ID：订单ID生成
     * 
     * @param voucherId 优惠券ID
     * @return 订单ID
     */
    @Override
    public Result seckillVoucher(Long voucherId) {
        // 1. 查询秒杀优惠券
        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);
        
        // 2. 判断秒杀时间
        // 2.1 判断秒杀是否开始
        if (voucher.getBeginTime().isAfter(LocalDateTime.now())) {
            // 秒杀尚未开始
            return Result.fail("秒杀尚未开始！");
        }
        
        // 2.2 判断秒杀是否结束
        if (voucher.getEndTime().isBefore(LocalDateTime.now())) {
            // 秒杀已经结束
            return Result.fail("秒杀已经结束！");
        }
        
        // 3. 判断库存是否充足
        if (voucher.getStock() < 1) {
            // 库存不足
            return Result.fail("库存不足！");
        }
        
        // 4. 一人一单逻辑
        // 获取当前登录用户ID
        Long userId = UserHolder.getUser().getId();
        
        // 使用用户ID作为锁对象，确保同一个用户的多个请求串行执行
        // 为什么要用 toString().intern()？
        // - toString() 会创建新的String对象，每次都不同
        // - intern() 会返回字符串常量池中的对象，相同内容的字符串返回同一个对象
        // - 这样同一个用户ID就会使用同一把锁
        synchronized (userId.toString().intern()) {
            // 获取代理对象（因为Spring事务是基于代理实现的）
            // 如果直接调用 this.createVoucherOrder()，事务不会生效
            // 因为 this 是当前对象，不是Spring的代理对象
            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
            return proxy.createVoucherOrder(voucherId);
        }
    }
    
    /**
     * 创建优惠券订单（事务方法）
     * 
     * 为什么要单独提取这个方法？
     * 1. 需要加 @Transactional 注解，保证数据一致性
     * 2. 需要通过代理对象调用，否则事务不生效
     * 
     * 事务包含的操作：
     * 1. 查询是否已购买（一人一单校验）
     * 2. 扣减库存（乐观锁）
     * 3. 创建订单
     * 
     * @param voucherId 优惠券ID
     * @return 订单ID
     */
    @Transactional
    public Result createVoucherOrder(Long voucherId) {
        // 1. 一人一单校验
        Long userId = UserHolder.getUser().getId();
        
        // 1.1 查询订单
        Long count = query()
                .eq("user_id", userId)
                .eq("voucher_id", voucherId)
                .count();
        
        // 1.2 判断是否存在
        if (count > 0) {
            // 用户已经购买过了
            return Result.fail("每人限购一张！");
        }
        
        // 2. 扣减库存（使用乐观锁解决超卖问题）
        // 
        // 为什么会超卖？
        // 假设库存为1：
        // 线程A：查询库存=1 → 判断通过 → 准备扣减
        // 线程B：查询库存=1 → 判断通过 → 准备扣减
        // 线程A：更新库存为0
        // 线程B：更新库存为-1（超卖！）
        //
        // 乐观锁方案（CAS - Compare And Swap）：
        // UPDATE ... SET stock = stock - 1 WHERE id = ? AND stock > 0
        // 这个SQL保证了：只有当stock > 0时才会执行更新
        // 如果多个线程同时执行，只有一个能成功（stock变为0后，其他线程的WHERE条件不满足）
        //
        // 优点：
        // - 无锁，性能好
        // - 数据库层面保证原子性
        // 缺点：
        // - 可能失败率较高（库存充足但更新失败）
        // - 需要重试机制（本例中直接返回失败）
        boolean success = seckillVoucherService.update()
                .setSql("stock = stock - 1")  // set stock = stock - 1
                .eq("voucher_id", voucherId)  // where voucher_id = ?
                .gt("stock", 0)  // and stock > 0（乐观锁的关键！）
                .update();
        
        if (!success) {
            // 扣减库存失败
            return Result.fail("库存不足！");
        }
        
        // 3. 创建订单
        VoucherOrder voucherOrder = new VoucherOrder();
        
        // 3.1 订单ID（使用全局唯一ID生成器）
        long orderId = redisIdWorker.nextId("order");
        voucherOrder.setId(orderId);
        
        // 3.2 用户ID
        voucherOrder.setUserId(userId);
        
        // 3.3 优惠券ID
        voucherOrder.setVoucherId(voucherId);
        
        // 3.4 保存订单
        save(voucherOrder);
        
        // 4. 返回订单ID
        return Result.ok(orderId);
    }
}
