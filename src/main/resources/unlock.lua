-- Redis Lua脚本：释放分布式锁
-- 
-- 功能：判断锁是否是当前线程持有，如果是则删除
-- 
-- 参数：
--   KEYS[1]：锁的key
--   ARGV[1]：线程标识（UUID + 线程ID）
-- 
-- 返回值：
--   1：成功释放锁
--   0：锁不存在或不是当前线程持有

-- 比较线程标识与锁标识是否一致
if (redis.call('get', KEYS[1]) == ARGV[1]) then
    -- 一致则删除锁
    return redis.call('del', KEYS[1])
end

-- 不一致则返回0
return 0

