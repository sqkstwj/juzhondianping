# Postman 测试指南 - 添加秒杀券

## 📋 接口信息

### 添加秒杀券
- **接口地址**: `POST http://localhost:8081/voucher/seckill`
- **请求头**: `Content-Type: application/json`
- **认证**: 需要登录（需要在Header中添加token）

---

## 🚀 快速开始

### 步骤1：登录获取Token

**接口**: `POST http://localhost:8081/user/login`

**Body (raw JSON)**:
```json
{
    "phone": "13812345678",
    "code": "123456"
}
```

**响应**:
```json
{
    "success": true,
    "data": "f47e1c5a-8d9b-4f2e-a3c1-9b7e4f5d6c8a"
}
```

**复制这个token**，在后续请求的Header中使用。

---

### 步骤2：添加秒杀券

**接口**: `POST http://localhost:8081/voucher/seckill`

**Headers**:
```
Content-Type: application/json
authorization: f47e1c5a-8d9b-4f2e-a3c1-9b7e4f5d6c8a
```

---

## 📝 Body Raw 数据示例

### 示例1：基础秒杀券（推荐用于测试）

```json
{
    "shopId": 1,
    "title": "100元代金券",
    "subTitle": "周一至周五可用",
    "rules": "全场通用\\n无需预约\\n可无限叠加\\不兑现、不找零\\n仅限堂食",
    "payValue": 8000,
    "actualValue": 10000,
    "type": 1,
    "stock": 100,
    "beginTime": "2025-10-22T10:00:00",
    "endTime": "2025-10-22T23:59:59"
}
```

**字段说明**:
- `shopId`: 商铺ID（1表示第一个商铺）
- `title`: 优惠券标题
- `subTitle`: 副标题
- `rules`: 使用规则
- `payValue`: 支付金额（单位：分，8000 = 80元）
- `actualValue`: 抵扣金额（单位：分，10000 = 100元）
- `type`: 类型（1=秒杀券）
- `stock`: 库存（100张）
- `beginTime`: 开始时间（今天10点开始）
- `endTime`: 结束时间（今天23:59结束）

---

### 示例2：立即可用的秒杀券（当前时间可秒杀）

```json
{
    "shopId": 1,
    "title": "50元代金券",
    "subTitle": "限时秒杀",
    "rules": "全场通用",
    "payValue": 4000,
    "actualValue": 5000,
    "type": 1,
    "stock": 50,
    "beginTime": "2025-10-22T00:00:00",
    "endTime": "2025-10-23T23:59:59"
}
```

---

### 示例3：大额秒杀券（测试高并发）

```json
{
    "shopId": 1,
    "title": "200元代金券",
    "subTitle": "秒杀专享",
    "rules": "全场通用\\n每人限购1张",
    "payValue": 18000,
    "actualValue": 20000,
    "type": 1,
    "stock": 10,
    "beginTime": "2025-10-22T00:00:00",
    "endTime": "2025-10-23T23:59:59"
}
```

**说明**: 库存只有10张，适合测试高并发超卖问题

---

### 示例4：未来秒杀券（测试秒杀未开始）

```json
{
    "shopId": 1,
    "title": "88元代金券",
    "subTitle": "明天开抢",
    "rules": "全场通用",
    "payValue": 6800,
    "actualValue": 8800,
    "type": 1,
    "stock": 200,
    "beginTime": "2025-10-23T10:00:00",
    "endTime": "2025-10-23T23:59:59"
}
```

**说明**: 开始时间是明天，测试"秒杀尚未开始"的场景

---

### 示例5：已结束秒杀券（测试秒杀已结束）

```json
{
    "shopId": 1,
    "title": "30元代金券",
    "subTitle": "已结束",
    "rules": "全场通用",
    "payValue": 2500,
    "actualValue": 3000,
    "type": 1,
    "stock": 100,
    "beginTime": "2025-10-21T10:00:00",
    "endTime": "2025-10-21T23:59:59"
}
```

**说明**: 结束时间是昨天，测试"秒杀已经结束"的场景

---

## 🎯 完整测试流程

### 1. 添加秒杀券

**请求**:
```
POST http://localhost:8081/voucher/seckill
Content-Type: application/json
authorization: {你的token}

Body:
{
    "shopId": 1,
    "title": "100元代金券",
    "subTitle": "限时秒杀",
    "rules": "全场通用",
    "payValue": 8000,
    "actualValue": 10000,
    "type": 1,
    "stock": 100,
    "beginTime": "2025-10-22T00:00:00",
    "endTime": "2025-10-23T23:59:59"
}
```

**预期响应**:
```json
{
    "success": true,
    "data": 10
}
```

**data字段**是生成的优惠券ID，记住这个ID！

---

### 2. 查询秒杀券列表

**请求**:
```
GET http://localhost:8081/voucher/list/1
```

**预期响应**:
```json
{
    "success": true,
    "data": [
        {
            "id": 10,
            "shopId": 1,
            "title": "100元代金券",
            "subTitle": "限时秒杀",
            "rules": "全场通用",
            "payValue": 8000,
            "actualValue": 10000,
            "type": 1,
            "status": null,
            "stock": 100,
            "beginTime": "2025-10-22T00:00:00",
            "endTime": "2025-10-23T23:59:59",
            "createTime": "2025-10-22T11:30:00",
            "updateTime": "2025-10-22T11:30:00"
        }
    ]
}
```

---

### 3. 秒杀优惠券

**请求**:
```
POST http://localhost:8081/voucher-order/seckill/10
authorization: {你的token}
```

**预期响应**:
```json
{
    "success": true,
    "data": 1729573824512000001
}
```

**data字段**是生成的订单ID

---

### 4. 再次秒杀（测试一人一单）

**请求**: 同一个用户再次请求秒杀

**预期响应**:
```json
{
    "success": false,
    "errorMsg": "每人限购一张！"
}
```

---

## 🔧 常见问题

### Q1: 时间格式错误

❌ **错误格式**:
```json
"beginTime": "2025-10-22 10:00:00"
```

✅ **正确格式**:
```json
"beginTime": "2025-10-22T10:00:00"
```

**注意**: 日期和时间之间用 `T` 连接！

---

### Q2: 金额单位

❌ **错误**:
```json
"payValue": 80,
"actualValue": 100
```

✅ **正确**:
```json
"payValue": 8000,
"actualValue": 10000
```

**说明**: 金额单位是**分**，不是元！

---

### Q3: 没有登录

**错误响应**:
```json
{
    "success": false,
    "errorMsg": "用户未登录"
}
```

**解决**: 先登录获取token，然后在Header中添加 `authorization: {token}`

---

### Q4: shopId不存在

**错误**: 数据库中没有对应的商铺

**解决**: 
```sql
-- 查询商铺ID
SELECT id, name FROM tb_shop LIMIT 10;

-- 使用查询到的shopId
```

---

## 📊 测试数据生成器

### 快速生成多个秒杀券（不同面额）

**20元券**:
```json
{
    "shopId": 1,
    "title": "20元代金券",
    "subTitle": "限时秒杀",
    "rules": "全场通用",
    "payValue": 1500,
    "actualValue": 2000,
    "type": 1,
    "stock": 200,
    "beginTime": "2025-10-22T00:00:00",
    "endTime": "2025-10-23T23:59:59"
}
```

**50元券**:
```json
{
    "shopId": 1,
    "title": "50元代金券",
    "subTitle": "限时秒杀",
    "rules": "全场通用",
    "payValue": 4000,
    "actualValue": 5000,
    "type": 1,
    "stock": 100,
    "beginTime": "2025-10-22T00:00:00",
    "endTime": "2025-10-23T23:59:59"
}
```

**100元券**:
```json
{
    "shopId": 1,
    "title": "100元代金券",
    "subTitle": "限时秒杀",
    "rules": "全场通用",
    "payValue": 8000,
    "actualValue": 10000,
    "type": 1,
    "stock": 50,
    "beginTime": "2025-10-22T00:00:00",
    "endTime": "2025-10-23T23:59:59"
}
```

---

## 🎓 测试场景建议

### 场景1：基础功能测试
1. 添加一个库存100的秒杀券
2. 秒杀成功
3. 查看库存变为99

### 场景2：一人一单测试
1. 用户A秒杀成功
2. 用户A再次秒杀 → 失败（每人限购一张）
3. 用户B秒杀 → 成功

### 场景3：库存不足测试
1. 添加库存=1的秒杀券
2. 用户A秒杀 → 成功
3. 用户B秒杀 → 失败（库存不足）

### 场景4：时间限制测试
1. 添加未来开始的秒杀券
2. 立即秒杀 → 失败（秒杀尚未开始）
3. 添加已结束的秒杀券
4. 秒杀 → 失败（秒杀已经结束）

### 场景5：高并发测试（JMeter）
1. 添加库存=10的秒杀券
2. JMeter 100并发请求
3. 验证：只有10个成功，90个失败
4. 验证：库存=0，订单数=10

---

## 💡 小技巧

### 1. Postman环境变量

设置环境变量方便复用：
```
baseUrl: http://localhost:8081
token: f47e1c5a-8d9b-4f2e-a3c1-9b7e4f5d6c8a
voucherId: 10
```

使用变量：
```
{{baseUrl}}/voucher/seckill
authorization: {{token}}
```

### 2. 自动获取当前时间

使用Postman的预请求脚本：
```javascript
// 当前时间
pm.environment.set("now", new Date().toISOString());

// 1小时后
let oneHourLater = new Date();
oneHourLater.setHours(oneHourLater.getHours() + 1);
pm.environment.set("oneHourLater", oneHourLater.toISOString());
```

然后在Body中使用：
```json
{
    "beginTime": "{{now}}",
    "endTime": "{{oneHourLater}}"
}
```

---

祝测试顺利！🎉

