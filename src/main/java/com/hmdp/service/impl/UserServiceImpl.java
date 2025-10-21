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
        //校验手机号
        String phone = loginForm.getPhone();
        if(RegexUtils.isPhoneInvalid(phone)) {
            //如果不符合，返回错误
            return Result.fail("号码格式错误！");
        }
        // 从redis校验验证码

        String  cacheCode = stringRedisTemplate.opsForValue().get(LOGIN_CODE_KEY + phone);
        String code = loginForm.getCode();
        if(cacheCode == null || !cacheCode.equals(code)){
        //不一致，报错
            return Result.fail("验证码错误");
        }

        //一致根据手机号查询用户
        User user = query().eq("phone",phone).one();
        //判断用户是否存在
        if(user == null){
            //不存在就创建用户并保存
            user = createUserWithPhone(phone);
        }

        //存在就直接保存到redis中
        //生成一个token,作为登录令牌
        String token = UUID.randomUUID().toString(true);
        //将User对象转化为Hash存储
        UserDTO userDTO = BeanUtil.copyProperties(user,UserDTO.class);
        Map<String , Object> userMap = BeanUtil.beanToMap(userDTO,new HashMap<>(),
                CopyOptions.create()
                        .setIgnoreNullValue(true)
                        .setFieldValueEditor((fieldName, fieldValue) -> fieldValue.toString()));
        //存储
        stringRedisTemplate.opsForHash().putAll(LOGIN_USER_KEY + token,userMap);

        //设置token有效期
        stringRedisTemplate.expire(LOGIN_USER_KEY+token, LOGIN_USER_TTL, TimeUnit.MINUTES);
        
        //登录成功后删除验证码，防止重复使用
        stringRedisTemplate.delete(LOGIN_CODE_KEY + phone);

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

    private User createUserWithPhone(String phone){
        User user = new User();
        user.setPhone(phone);
        user.setNickName(USER_NICK_NAME_PREFIX+ RandomUtil.randomString(10));

        save(user);
        return user;
    }
}
