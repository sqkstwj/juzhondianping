# 黑马点评项目 - 本地生活服务平台

## 📖 项目简介

基于 Spring Boot 开发的本地生活服务平台，实现了用户认证、商铺查询、缓存优化等核心功能。项目采用 Redis 缓存技术优化查询性能，通过双拦截器实现用户认证，并针对缓存穿透、雪崩、击穿三大问题实现了完善的解决方案。

经过 JMeter 压测验证，系统在 1000 并发下平均响应时间达到 1ms，性能提升 10 倍。

## 🛠️ 技术栈

- **后端框架**：Spring Boot 2.7.4、Spring MVC
- **持久层**：MyBatis-Plus 3.5.2、MySQL
- **缓存中间件**：Redis (Lettuce 连接池)
- **工具库**：Hutool 5.8.8、Lombok
- **性能测试**：Apache JMeter

## ✨ 已完成功能

### 1. 用户认证模块 ✅
- 短信验证码登录（验证码2分钟过期）
- 基于 Redis + Token 的无状态认证
- 双拦截器机制（Token刷新 + 权限校验）
- ThreadLocal 实现用户上下文管理
- 登出功能（主动删除Redis会话）

### 2. 商铺缓存模块 ✅
- 商铺信息查询（Cache Aside Pattern）
- 商铺类型列表查询（列表缓存）
- 缓存更新策略（先更新数据库，再删除缓存）
- 缓存预热接口（用于逻辑过期方案）

### 3. 缓存问题解决方案 ✅

#### 缓存穿透
- **问题**：恶意查询不存在的数据，导致请求都打到数据库
- **方案**：缓存空对象，设置2分钟过期时间

#### 缓存雪崩
- **问题**：大量缓存同时过期，瞬间压垮数据库
- **方案**：随机过期时间
  - 商铺缓存：30-39分钟（基础30分钟 + 随机0-9分钟）
  - 商铺类型：30-36天（基础30天 + 随机0-6天）

#### 缓存击穿（两种方案）
- **问题**：热点数据过期瞬间，大量并发请求打到数据库

**方案1：互斥锁方案**
- 基于 Redis SETNX 实现分布式锁
- 保证数据强一致性
- 性能：平均10ms，最大322ms
- 适用场景：对一致性要求高的业务

**方案2：逻辑过期方案** ⭐ 推荐
- 缓存永不过期，数据中带逻辑过期时间
- 线程池异步重建缓存，查询线程立即返回
- 性能：平均1ms，最大5ms
- 适用场景：对性能要求高的热点数据

## 📊 性能测试结果

### JMeter 1000并发压测对比

| 方案 | 平均响应时间 | 最大响应时间 | 标准偏差 | 吞吐量 |
|------|-------------|-------------|---------|--------|
| 互斥锁方案 | 10ms | 322ms | 41.51 | 199/sec |
| 逻辑过期方案 | **1ms** ⭐ | **5ms** ⭐ | **0.58** ⭐ | 199/sec |

**性能提升：**
- ✅ 平均响应时间提升 **10倍**
- ✅ 最大响应时间提升 **64倍**
- ✅ 稳定性提升 **71倍**

## 🚀 快速开始

### 环境要求
- JDK 8+
- Maven 3.6+
- MySQL 5.7+
- Redis 6.0+

### 1. 克隆项目
```bash
git clone https://github.com/sqkstwj/juzhondianping.git
cd juzhondianping
```

### 2. 导入数据库
```bash
# 执行SQL文件创建数据库和表
mysql -u root -p < hmdp.sql
```

### 3. 修改配置
编辑 `src/main/resources/application.yaml`：
```yaml
spring:
  datasource:
    url: jdbc:mysql://127.0.0.1:3306/hmdp
    username: root
    password: your_password
  redis:
    host: 127.0.0.1
    port: 6379
    password: your_redis_password  # 如果有密码
```

### 4. 启动Redis
```bash
redis-server
```

### 5. 运行项目
```bash
mvn spring-boot:run
```
或在IDEA中直接运行 `HmDianPingApplication`

### 6. 访问前端页面
```
http://localhost:8080/hmdp/index.html
```

## 📁 项目结构

```
hmdp-init/
├── src/main/java/com/hmdp/
│   ├── config/           # 配置类（拦截器、MyBatis等）
│   ├── controller/       # 控制器层
│   ├── dto/              # 数据传输对象
│   ├── entity/           # 实体类
│   ├── mapper/           # Mapper接口
│   ├── service/          # 服务层
│   │   └── impl/         # 服务实现类
│   └── utils/            # 工具类
│       ├── LoginInterceptor.java           # 登录拦截器
│       ├── RefreshTokenInterceptor.java    # Token刷新拦截器
│       ├── RedisConstants.java             # Redis常量
│       ├── RedisData.java                  # 逻辑过期数据结构
│       └── UserHolder.java                 # ThreadLocal用户持有者
├── src/main/resources/
│   ├── application.yaml                    # 应用配置
│   └── nginx-1.18.0/                       # 前端资源
├── .gitignore
├── pom.xml
└── README.md
```

## 🎯 核心代码说明

### 双拦截器机制
```java
// RefreshTokenInterceptor: 刷新Token，order=0
// LoginInterceptor: 校验登录，order=1
registry.addInterceptor(new RefreshTokenInterceptor(stringRedisTemplate))
        .addPathPatterns("/**").order(0);
registry.addInterceptor(new LoginInterceptor())
        .excludePathPatterns("/user/code", "/user/login", "/shop/**")
        .order(1);
```

### 逻辑过期方案数据结构
```java
{
  "data": {          // 实际的商铺数据
    "id": 1,
    "name": "海底捞火锅",
    ...
  },
  "expireTime": "2024-10-20T16:00:00"  // 逻辑过期时间
}
```

### 缓存预热接口
```bash
# 预热商铺缓存（设置逻辑过期时间为3600秒）
GET http://localhost:8081/shop/cache/1?expire=3600
```

## 🔧 技术亮点

1. **缓存架构设计**
   - 针对不同场景选择合适的缓存方案
   - 实现完善的缓存更新策略（Cache Aside Pattern）
   - 解决缓存穿透、雪崩、击穿三大经典问题

2. **高并发优化**
   - 双拦截器 + ThreadLocal 实现高性能用户上下文管理
   - Redis 分布式锁解决缓存击穿问题
   - 线程池异步重建缓存，保证响应速度

3. **性能验证**
   - 使用 JMeter 进行 1000 并发压测
   - 对比两种缓存击穿方案性能差异
   - 逻辑过期方案实现平均 1ms 响应时间

4. **代码质量**
   - 防御性编程，处理边界情况
   - DTO 模式隔离敏感信息
   - 详细的代码注释和日志输出

## 📈 开发进度

- [x] 用户认证模块（短信验证码 + Token登录）
- [x] 商铺查询缓存
- [x] 商铺类型列表缓存
- [x] 缓存穿透解决方案
- [x] 缓存雪崩解决方案
- [x] 缓存击穿解决方案（互斥锁 + 逻辑过期）
- [x] JMeter性能测试
- [ ] 优惠券秒杀功能
- [ ] 点赞/收藏功能
- [ ] 关注功能
- [ ] Feed流推送
- [ ] 附近商铺（GEO）
- [ ] 用户签到（BitMap）
- [ ] UV统计（HyperLogLog）

## 🤝 贡献指南

欢迎提交 Issue 和 Pull Request！

1. Fork 本项目
2. 创建你的特性分支 (`git checkout -b feature/AmazingFeature`)
3. 提交你的改动 (`git commit -m 'feat: Add some AmazingFeature'`)
4. 推送到分支 (`git push origin feature/AmazingFeature`)
5. 提交 Pull Request

## 📝 开发日志

### 2024-10-20
- ✅ 完成用户认证模块
- ✅ 实现商铺缓存功能
- ✅ 解决缓存三大问题
- ✅ 完成性能压测对比

## 📧 联系方式

如有问题，欢迎通过以下方式联系：
- GitHub Issues: [提交Issue](https://github.com/sqkstwj/juzhondianping/issues)

## 📄 License

本项目仅供学习交流使用。

---

⭐ 如果这个项目对你有帮助，欢迎 Star！
