package com.hmdp.controller;


import com.hmdp.dto.Result;
import com.hmdp.service.IVoucherOrderService;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.Resource;

/**
 * <p>
 *  优惠券订单控制器
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@RestController
@RequestMapping("/voucher-order")
public class VoucherOrderController {
    
    @Resource
    private IVoucherOrderService voucherOrderService;
    
    /**
     * 秒杀优惠券
     * 
     * 接口说明：
     * POST /voucher-order/seckill/{id}
     * 
     * 参数：
     * - id: 优惠券ID（路径参数）
     * 
     * 返回：
     * - 成功：订单ID
     * - 失败：错误信息（库存不足、秒杀未开始、已购买等）
     * 
     * @param voucherId 优惠券ID
     * @return 秒杀结果
     */
    @PostMapping("seckill/{id}")
    public Result seckillVoucher(@PathVariable("id") Long voucherId) {
        return voucherOrderService.seckillVoucher(voucherId);
    }
}
