# JMeter 秒杀压测指南

## 📋 测试目标

验证秒杀功能在高并发场景下：
1. ✅ **超卖问题** - 是否会超卖（乐观锁验证）
2. ✅ **一人一单** - 同一用户是否能重复购买
3. ✅ **性能指标** - TPS、响应时间、成功率
4. ✅ **库存一致性** - 最终库存是否正确

---

## 🎯 测试场景设计

### 场景1：单用户并发测试（验证一人一单）
- **用户数**：1个
- **并发线程**：100
- **预期结果**：只有1个订单成功，其他99个失败（每人限购一张）

### 场景2：多用户并发测试（验证超卖）
- **用户数**：200个
- **库存数量**：100
- **并发线程**：200
- **预期结果**：成功100个订单，失败100个，库存为0

### 场景3：性能压测
- **用户数**：1000个
- **库存数量**：1000
- **并发线程**：1000
- **测试时间**：持续压测观察TPS和响应时间

---

## 📦 准备工作

### Step 1: 准备秒杀券数据

**方法1：使用 Postman 添加**

```
POST http://localhost:8081/voucher/seckill

Body (raw JSON):
{
    "shopId": 1,
    "title": "【JMeter压测】100元代金券",
    "subTitle": "周一至周日均可使用",
    "rules": "全场通用",
    "payValue": 8000,
    "actualValue": 10000,
    "type": 1,
    "stock": 100,
    "beginTime": "2025-01-01T00:00:00",
    "endTime": "2025-12-31T23:59:59"
}
```

**响应：**
```json
{
    "success": true,
    "data": 10  // 记住这个优惠券ID
}
```

**方法2：直接插入数据库**

```sql
-- 1. 插入优惠券
INSERT INTO tb_voucher (shop_id, title, sub_title, rules, pay_value, actual_value, type, status, create_time, update_time) 
VALUES (1, '【JMeter压测】100元代金券', '周一至周日均可使用', '全场通用', 8000, 10000, 1, 1, NOW(), NOW());

-- 2. 获取优惠券ID
SELECT LAST_INSERT_ID(); -- 假设返回 10

-- 3. 插入秒杀券信息
INSERT INTO tb_seckill_voucher (voucher_id, stock, begin_time, end_time, create_time, update_time) 
VALUES (10, 100, '2025-01-01 00:00:00', '2025-12-31 23:59:59', NOW(), NOW());
```

---

### Step 2: 准备测试用户（重要！）

由于有**一人一单**限制，需要准备多个测试用户。

#### 方案1：批量创建用户（推荐）

**创建SQL脚本：**

```sql
-- 批量插入200个测试用户
INSERT INTO tb_user (phone, password, nick_name, icon, create_time, update_time) 
VALUES 
('13800000001', NULL, 'user_test_001', '', NOW(), NOW()),
('13800000002', NULL, 'user_test_002', '', NOW(), NOW()),
('13800000003', NULL, 'user_test_003', '', NOW(), NOW()),
-- ... 继续到
('13800000200', NULL, 'user_test_200', '', NOW(), NOW());
```

**快速生成脚本（Python）：**

```python
# generate_users.py
for i in range(1, 201):
    phone = f"138000{i:05d}"
    nickname = f"user_test_{i:03d}"
    print(f"('{phone}', NULL, '{nickname}', '', NOW(), NOW()),")
```

---

### Step 3: 准备用户Token

#### 方案1：使用CSV文件（推荐）

**1. 批量获取Token（Python脚本）**

```python
# get_tokens.py
import requests
import json

# 发送验证码并登录获取token
tokens = []
for i in range(1, 201):
    phone = f"138000{i:05d}"
    
    # 发送验证码
    resp1 = requests.post(f"http://localhost:8081/user/code?phone={phone}")
    print(f"发送验证码: {phone}")
    
    # 从控制台复制验证码（实际使用时可以固定验证码或修改代码逻辑）
    # 这里假设你修改了代码，固定验证码为 123456
    code = "123456"
    
    # 登录获取token
    resp2 = requests.post("http://localhost:8081/user/login",
                         json={"phone": phone, "code": code})
    token = resp2.json()['data']
    tokens.append(f"{phone},{token}")
    print(f"登录成功: {phone} -> {token[:20]}...")

# 保存到CSV文件
with open('tokens.csv', 'w') as f:
    f.write("phone,token\n")
    for line in tokens:
        f.write(line + "\n")

print(f"\n生成完成！共 {len(tokens)} 个用户token")
```

**2. 简化方案：手动创建tokens.csv**

```csv
phone,token
13800000001,token1-xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx
13800000002,token2-xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx
13800000003,token3-xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx
```

---

## 🚀 JMeter 配置详解

### Step 1: 创建测试计划

1. 打开 JMeter
2. 右键 `Test Plan` → `Add` → `Threads (Users)` → `Thread Group`

**配置线程组：**

| 参数 | 场景1（一人一单） | 场景2（超卖） | 场景3（性能）|
|------|------------------|---------------|-------------|
| Number of Threads | 100 | 200 | 1000 |
| Ramp-up Period | 1 | 1 | 5 |
| Loop Count | 1 | 1 | 1 |

**说明：**
- `Number of Threads`：并发用户数
- `Ramp-up Period`：多少秒内启动所有线程（1秒=瞬间启动，模拟真实秒杀）
- `Loop Count`：每个线程执行次数

---

### Step 2: 添加 CSV Data Set Config（重要！）

**右键 Thread Group → Add → Config Element → CSV Data Set Config**

**配置：**
```
Filename: tokens.csv                    # CSV文件路径
File Encoding: UTF-8
Variable Names: phone,token             # 变量名（与CSV列名对应）
Delimiter: ,                            # 分隔符
Recycle on EOF: True                    # 文件读完后重新开始
Stop thread on EOF: False               # 文件读完不停止线程
Sharing mode: All threads              # 所有线程共享（重要！）
```

**关键点：**
- `Sharing mode: All threads` - 确保每个线程使用不同的token
- 如果只测试一人一单，可以只准备1个token，设置 `Recycle on EOF: True`

---

### Step 3: 添加 HTTP Request

**右键 Thread Group → Add → Sampler → HTTP Request**

**配置：**
```
Name: 秒杀优惠券

Protocol: http
Server Name or IP: localhost
Port Number: 8081
HTTP Request Method: POST
Path: /voucher-order/seckill/10      # 10是优惠券ID，改成你的

Body Data: 留空（POST请求，无需body）
```

**添加 HTTP Header Manager（必须！）：**

右键 `HTTP Request` → `Add` → `Config Element` → `HTTP Header Manager`

添加请求头：
```
Name: authorization
Value: ${token}                        # 使用CSV中的token变量
```

---

### Step 4: 添加监听器（查看结果）

右键 `Thread Group` → `Add` → `Listener`

**推荐添加以下监听器：**

1. **View Results Tree** - 查看每个请求的详细结果
2. **Summary Report** - 查看汇总统计（TPS、响应时间等）
3. **Aggregate Report** - 查看聚合报告
4. **Response Time Graph** - 查看响应时间图表

---

## 🎯 测试执行

### 场景1：单用户并发（验证一人一单）

**配置：**
```
Thread Group:
  - Number of Threads: 100
  - Ramp-up: 1
  - Loop: 1

CSV Data Set:
  - 只准备1个用户token
  - Recycle on EOF: True  # 所有线程使用同一个token

HTTP Request:
  - Path: /voucher-order/seckill/10
  - Header: authorization = ${token}
```

**执行测试：**

1. 点击 `运行` 按钮（绿色三角形）
2. 观察 `View Results Tree`
3. 查看 `Summary Report`

**预期结果：**
```
Summary Report:
  - 总请求数: 100
  - 成功: 1     ✅（第一个请求成功）
  - 失败: 99    ✅（其他请求返回"每人限购一张！"）
  - Error%: 99%
```

**验证数据库：**
```sql
-- 查询订单数量（应该只有1条）
SELECT COUNT(*) FROM tb_voucher_order WHERE voucher_id = 10;
-- 结果: 1

-- 查询库存（应该减少1）
SELECT stock FROM tb_seckill_voucher WHERE voucher_id = 10;
-- 结果: 99（原来100）
```

---

### 场景2：多用户并发（验证超卖）

**配置：**
```
Thread Group:
  - Number of Threads: 200
  - Ramp-up: 1
  - Loop: 1

CSV Data Set:
  - 准备200个用户token
  - Sharing mode: All threads  # 每个线程使用不同token

秒杀券:
  - stock: 100  # 库存设置为100
```

**执行测试：**

1. **重置数据：**
```sql
-- 重置库存
UPDATE tb_seckill_voucher SET stock = 100 WHERE voucher_id = 10;

-- 删除测试订单
DELETE FROM tb_voucher_order WHERE voucher_id = 10;
```

2. **运行 JMeter**

3. **观察结果**

**预期结果：**
```
Summary Report:
  - 总请求数: 200
  - 成功: 100    ✅（库存为100，成功100个）
  - 失败: 100    ✅（库存不足，失败100个）
  - Error%: 50%
```

**验证数据库（关键！）：**
```sql
-- 查询订单数量
SELECT COUNT(*) FROM tb_voucher_order WHERE voucher_id = 10;
-- 结果: 100  ✅（正好100，没有超卖）

-- 查询库存
SELECT stock FROM tb_seckill_voucher WHERE voucher_id = 10;
-- 结果: 0  ✅（库存为0）

-- 检查是否有超卖（库存为负）
SELECT * FROM tb_seckill_voucher WHERE voucher_id = 10 AND stock < 0;
-- 结果: 空  ✅（没有超卖）
```

---

### 场景3：性能压测

**配置：**
```
Thread Group:
  - Number of Threads: 1000
  - Ramp-up: 5
  - Loop: 1

秒杀券:
  - stock: 1000
```

**执行测试：**

**观察指标：**
```
Summary Report:
  - Throughput (TPS): 观察每秒处理请求数
  - Average Response Time: 平均响应时间
  - 90% Line: 90%的请求响应时间
  - Error%: 错误率
```

**性能基准（参考）：**
```
✅ 优秀：
  - TPS: > 500
  - 平均响应时间: < 100ms
  - 90% Line: < 200ms

⚠️ 一般：
  - TPS: 200-500
  - 平均响应时间: 100-500ms
  - 90% Line: 200-1000ms

❌ 较差：
  - TPS: < 200
  - 平均响应时间: > 500ms
  - 90% Line: > 1000ms
```

---

## 📊 结果分析

### 1. 查看 Summary Report

**关键指标：**

| 指标 | 说明 | 目标 |
|------|------|------|
| Samples | 总请求数 | - |
| Average | 平均响应时间(ms) | < 100ms |
| Min | 最小响应时间(ms) | - |
| Max | 最大响应时间(ms) | < 1000ms |
| Std. Dev. | 标准差 | 越小越稳定 |
| Error% | 错误率 | 根据场景判断 |
| Throughput | 吞吐量(TPS) | > 500 |
| KB/sec | 网络带宽 | - |

---

### 2. 查看 View Results Tree

**成功响应示例：**
```json
{
    "success": true,
    "data": 1234567890123456789  // 订单ID
}
```

**失败响应示例：**
```json
{
    "success": false,
    "errorMsg": "库存不足！"
}
```

```json
{
    "success": false,
    "errorMsg": "每人限购一张！"
}
```

---

### 3. 验证数据一致性

**SQL验证脚本：**

```sql
-- 1. 查询订单总数
SELECT COUNT(*) AS order_count FROM tb_voucher_order WHERE voucher_id = 10;

-- 2. 查询剩余库存
SELECT stock FROM tb_seckill_voucher WHERE voucher_id = 10;

-- 3. 验证公式：初始库存 = 订单数 + 剩余库存
-- 假设初始库存100，订单数应该 + 库存数 = 100

-- 4. 检查是否有重复购买（一人一单）
SELECT user_id, COUNT(*) as buy_count 
FROM tb_voucher_order 
WHERE voucher_id = 10 
GROUP BY user_id 
HAVING buy_count > 1;
-- 结果应该为空

-- 5. 检查是否超卖
SELECT * FROM tb_seckill_voucher WHERE voucher_id = 10 AND stock < 0;
-- 结果应该为空
```

---

## 🐛 常见问题

### Q1: 所有请求都返回401

**原因：** Token无效或未登录

**解决：**
1. 检查 `HTTP Header Manager` 是否添加 `authorization`
2. 检查 `CSV Data Set` 配置是否正确
3. 检查token是否有效（Redis中是否存在）

**测试token是否有效：**
```
GET http://localhost:8081/user/me
Headers:
  authorization: your-token-here
```

---

### Q2: 所有请求都返回"每人限购一张"

**原因：** 所有线程使用同一个token

**解决：**
1. 准备多个用户token
2. CSV Data Set 设置 `Sharing mode: All threads`
3. 确保CSV文件有足够多的token

---

### Q3: 数据库有超卖（stock为负数）

**原因：** 乐观锁失效

**检查：**
1. 确认SQL是否正确：
```java
.setSql("stock = stock - 1")
.eq("voucher_id", voucherId)
.gt("stock", 0)  // ← 必须有这个条件
```

2. 检查数据库事务隔离级别

---

### Q4: 成功率太低（大量"库存不足"）

**原因：** 乐观锁失败率高（正常现象）

**说明：**
- 乐观锁在高并发下会有较高失败率
- 多个线程同时更新，只有一个成功
- 这是正常的，**不是bug**

**优化方案：**
1. 使用Redis预减库存（下一步优化）
2. 添加重试机制
3. 使用分段库存

---

### Q5: 性能不如预期（TPS很低）

**可能原因：**
1. 数据库性能瓶颈（连接池、索引）
2. 事务锁竞争（synchronized块）
3. 网络延迟

**优化建议：**
1. 增加数据库连接池大小
2. 添加数据库索引（voucher_id, user_id）
3. 使用Redis缓存
4. 异步处理订单

---

## 📈 性能优化对比

### 当前版本（乐观锁）

**优点：**
- ✅ 防止超卖
- ✅ 实现简单
- ✅ 无需Redis分布式锁

**缺点：**
- ❌ 高并发下失败率高
- ❌ 数据库压力大
- ❌ TPS受限于数据库性能

**测试结果（参考）：**
```
并发: 1000
库存: 100
成功: 100
失败: 900  ← 失败率90%（乐观锁冲突）
TPS: ~200-300
平均响应时间: 50-100ms
```

---

### 优化版本（Redis预减库存）

**改进点：**
- ✅ Redis预减库存（秒级响应）
- ✅ 减少数据库压力
- ✅ 提高成功率

**预期结果：**
```
并发: 1000
库存: 100
成功: 100
失败: 900  ← 失败率90%（库存不足，而非锁冲突）
TPS: ~1000-2000  ← 提升5-10倍
平均响应时间: 10-20ms  ← 响应时间降低5倍
```

---

## 🎓 测试报告模板

### 测试报告

**测试时间：** 2025-10-22

**测试目标：** 验证秒杀功能的超卖问题和并发性能

**测试环境：**
- JDK版本：1.8
- Spring Boot版本：2.7.4
- 数据库：MySQL 5.7
- Redis版本：6.2

**测试数据：**
- 秒杀券ID：10
- 初始库存：100
- 测试用户：200个

**测试场景：**

| 场景 | 线程数 | Ramp-up | 预期结果 | 实际结果 | 是否通过 |
|------|--------|---------|---------|---------|----------|
| 一人一单 | 100 | 1s | 成功1，失败99 | 成功1，失败99 | ✅ |
| 超卖验证 | 200 | 1s | 成功100，失败100 | 成功100，失败100 | ✅ |
| 性能压测 | 1000 | 5s | TPS>500 | TPS=300 | ⚠️ |

**性能指标：**
```
平均响应时间：80ms
90% Line：150ms
TPS：300
错误率：90%（乐观锁冲突）
```

**数据一致性验证：**
```sql
-- 订单总数
SELECT COUNT(*) FROM tb_voucher_order WHERE voucher_id = 10;
结果：100  ✅

-- 剩余库存
SELECT stock FROM tb_seckill_voucher WHERE voucher_id = 10;
结果：0  ✅

-- 超卖检查
SELECT * FROM tb_seckill_voucher WHERE stock < 0;
结果：空  ✅

-- 重复购买检查
SELECT user_id, COUNT(*) FROM tb_voucher_order WHERE voucher_id = 10 GROUP BY user_id HAVING COUNT(*) > 1;
结果：空  ✅
```

**结论：**
1. ✅ 乐观锁成功防止超卖
2. ✅ 一人一单校验正常
3. ⚠️ 性能有待提升（建议使用Redis预减库存）

---

## 🚀 下一步优化方向

1. **Redis预减库存** - 减少数据库压力
2. **异步下单** - 提升响应速度
3. **Lua脚本** - 保证原子性
4. **消息队列** - 削峰填谷

---

恭喜！🎉 现在你可以开始压测了！

