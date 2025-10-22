package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.RandomUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RegexUtils;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;
import static com.hmdp.utils.SystemConstants.USER_NICK_NAME_PREFIX;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Slf4j
@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;


    @Override
    public Result sendCode(String phone, HttpSession session) {
        // 校验手机号
        if(RegexUtils.isPhoneInvalid(phone)) {
            //如果不符合，返回错误
            return Result.fail("号码格式错误！");
        }
            //符合，就生成验证码
        String code = RandomUtil.randomNumbers(6);
            //保存验证码
        //session.setAttribute("code",code);
        //保存到redis //set key value ex 120
        stringRedisTemplate.opsForValue().set(LOGIN_CODE_KEY + phone,code,LOGIN_CODE_TTL, TimeUnit.MINUTES);
            //发送验证码
        log.debug("发送验证码流程完成，验证码：{}",code);
            //返回ok
        return Result.ok();

    }

    @Override
    public Result login(LoginFormDTO loginForm, HttpSession session) {
        // 1. 校验手机号
        String phone = loginForm.getPhone();
        if(RegexUtils.isPhoneInvalid(phone)) {
            return Result.fail("号码格式错误！");
        }
        
        // 2. 判断登录方式：验证码登录 or 密码登录
        String code = loginForm.getCode();
        String password = loginForm.getPassword();
        
        User user;
        
        // 2.1 验证码登录（优先）
        if (StrUtil.isNotBlank(code)) {
            log.debug("使用验证码登录，手机号: {}", phone);
            user = loginByCode(phone, code);
            if (user == null) {
                return Result.fail("验证码错误");
            }
        } 
        // 2.2 密码登录
        else if (StrUtil.isNotBlank(password)) {
            log.debug("使用密码登录，手机号: {}", phone);
            user = loginByPassword(phone, password);
            if (user == null) {
                return Result.fail("手机号或密码错误");
            }
        } 
        // 2.3 两者都没有
        else {
            return Result.fail("请输入验证码或密码");
        }
        
        // 3. 生成token并保存到Redis
        return generateTokenAndSave(user);
    }
    
    /**
     * 验证码登录
     * 
     * @param phone 手机号
     * @param code 验证码
     * @return 用户对象，验证失败返回null
     */
    private User loginByCode(String phone, String code) {
        // 1. 从Redis获取验证码
        String cacheCode = stringRedisTemplate.opsForValue().get(LOGIN_CODE_KEY + phone);
        
        // 2. 验证码不存在或不匹配
        if (cacheCode == null || !cacheCode.equals(code)) {
            log.warn("验证码错误，phone: {}, 输入code: {}, 缓存code: {}", phone, code, cacheCode);
            return null;
        }
        
        // 3. 验证码正确，查询或创建用户
        User user = query().eq("phone", phone).one();
        if (user == null) {
            // 不存在就创建用户（注册）
            user = createUserWithPhone(phone);
            log.info("新用户注册，phone: {}, userId: {}", phone, user.getId());
        }
        
        // 4. 删除验证码，防止重复使用
        stringRedisTemplate.delete(LOGIN_CODE_KEY + phone);
        
        return user;
    }
    
    /**
     * 密码登录
     * 
     * @param phone 手机号
     * @param password 明文密码
     * @return 用户对象，验证失败返回null
     */
    private User loginByPassword(String phone, String password) {
        // 1. 查询用户
        User user = query().eq("phone", phone).one();
        
        // 2. 用户不存在
        if (user == null) {
            log.warn("用户不存在，phone: {}", phone);
            return null;
        }
        
        // 3. 用户未设置密码
        if (StrUtil.isBlank(user.getPassword())) {
            log.warn("用户未设置密码，phone: {}, userId: {}", phone, user.getId());
            return null;
        }
        
        // 4. 验证密码
        if (!com.hmdp.utils.PasswordEncoder.matches(user.getPassword(), password)) {
            log.warn("密码错误，phone: {}, userId: {}", phone, user.getId());
            return null;
        }
        
        log.info("密码登录成功，phone: {}, userId: {}", phone, user.getId());
        return user;
    }
    
    /**
     * 生成Token并保存用户信息到Redis
     * 
     * @param user 用户对象
     * @return Result包含token
     */
    private Result generateTokenAndSave(User user) {
        // 1. 生成token
        String token = UUID.randomUUID().toString(true);
        
        // 2. 将User对象转为UserDTO（不包含敏感信息）
        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
        
        // 3. 转为Map存储
        Map<String, Object> userMap = BeanUtil.beanToMap(userDTO, new HashMap<>(),
                CopyOptions.create()
                        .setIgnoreNullValue(true)
                        .setFieldValueEditor((fieldName, fieldValue) -> fieldValue.toString()));
        
        // 4. 保存到Redis
        String key = LOGIN_USER_KEY + token;
        stringRedisTemplate.opsForHash().putAll(key, userMap);
        
        // 5. 设置过期时间
        stringRedisTemplate.expire(key, LOGIN_USER_TTL, TimeUnit.MINUTES);
        
        log.info("用户登录成功，token: {}, userId: {}", token, user.getId());
        return Result.ok(token);
    }

    @Override
    public Result logout(HttpServletRequest request) {
        // 1. 获取请求头中的token
        String token = request.getHeader("authorization");
        if(StrUtil.isBlank(token)){
            return Result.fail("未找到登录信息");
        }
        
        // 2. 删除Redis中的用户信息
        String key = LOGIN_USER_KEY + token;
        stringRedisTemplate.delete(key);
        
        // 3. 清除ThreadLocal中的用户信息
        UserHolder.removeUser();
        
        log.debug("用户登出成功，token: {}", token);
        return Result.ok("登出成功");
    }

    @Override
    public Result setPassword(String phone, String code, String password) {
        // 1. 校验手机号
        if (RegexUtils.isPhoneInvalid(phone)) {
            return Result.fail("号码格式错误！");
        }
        
        // 2. 校验密码格式（6-20位）
        if (StrUtil.isBlank(password) || password.length() < 6 || password.length() > 20) {
            return Result.fail("密码长度必须为6-20位！");
        }
        
        // 3. 校验验证码
        String cacheCode = stringRedisTemplate.opsForValue().get(LOGIN_CODE_KEY + phone);
        if (cacheCode == null || !cacheCode.equals(code)) {
            return Result.fail("验证码错误");
        }
        
        // 4. 查询用户
        User user = query().eq("phone", phone).one();
        if (user == null) {
            return Result.fail("用户不存在，请先注册");
        }
        
        // 5. 加密密码并保存
        String encodedPassword = com.hmdp.utils.PasswordEncoder.encode(password);
        user.setPassword(encodedPassword);
        updateById(user);
        
        // 6. 删除验证码
        stringRedisTemplate.delete(LOGIN_CODE_KEY + phone);
        
        log.info("用户设置密码成功，phone: {}, userId: {}", phone, user.getId());
        return Result.ok("密码设置成功");
    }
    
    private User createUserWithPhone(String phone){
        User user = new User();
        user.setPhone(phone);
        user.setNickName(USER_NICK_NAME_PREFIX+ RandomUtil.randomString(10));

        save(user);
        return user;
    }
}
