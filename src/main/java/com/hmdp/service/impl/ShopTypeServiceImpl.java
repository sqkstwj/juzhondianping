package com.hmdp.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.List;
import java.util.Random;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.CACHE_SHOP_TYPE_KEY;
import static com.hmdp.utils.RedisConstants.CACHE_SHOP_TYPE_TTL;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;
    
    private static final Random RANDOM = new Random();
    
    @Override
    public Result queryTypeList() {
        String key = CACHE_SHOP_TYPE_KEY;

        //1.查询缓存
        String cacheJson = stringRedisTemplate.opsForValue().get(key);

        //2.缓存命中
        if(StrUtil.isNotBlank(cacheJson)){
            List<ShopType> typeLst = JSONUtil.toList(cacheJson,ShopType.class);
            return Result.ok(typeLst);
        }

        //3.未命中,查数据库
        List<ShopType> typeList = query().orderByAsc("sort").list();

        //4.数据库中也没有(防御)
        if(typeList == null || typeList.isEmpty()){
            return Result.fail("商店类型数据异常");
        }

        //5.写入缓存（添加随机值防止雪崩）
        long expireTime = CACHE_SHOP_TYPE_TTL + RANDOM.nextInt(7);  // 30~36天随机
        stringRedisTemplate.opsForValue().set(
                key,
                JSONUtil.toJsonStr(typeList),
                expireTime,
                TimeUnit.DAYS
        );

        //6.返回结果
        return Result.ok(typeList);
    }
}
