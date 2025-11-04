package com.hmdp.config;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Redisson配置类
 * 
 * 功能：配置Redisson客户端连接Redis
 * 
 * 配置说明：
 * - 单机模式（Single Server）
 * - 地址：redis://127.0.0.1:6379（Windows本地）
 * - 无密码认证
 * - 数据库：0（默认）
 * 
 * @author sqkstwj
 * @since 2025-11-03
 */
@Configuration
public class RedissonConfig {
    
    /**
     * 创建Redisson客户端
     * 
     * @return RedissonClient实例
     */
    @Bean
    public RedissonClient redissonClient() {
        // 1. 创建配置对象
        Config config = new Config();
        
        // 2. 配置单机模式
        // 注意：地址格式必须是 redis://host:port
        // Windows本地Redis通常是 127.0.0.1:6379
        config.useSingleServer()
                // Redis地址（必须加 redis:// 前缀）
                .setAddress("redis://127.0.0.1:6379")
                
                // 密码（如果Redis没有设置密码，注释掉这一行或设置为null）
                // .setPassword("your_password")
                
                // 数据库编号（默认是0）
                .setDatabase(0)
                
                // 连接池大小（默认64）
                .setConnectionPoolSize(64)
                
                // 最小空闲连接数（默认24）
                .setConnectionMinimumIdleSize(24);
        
        // 3. 创建Redisson客户端
        return Redisson.create(config);
    }
}
