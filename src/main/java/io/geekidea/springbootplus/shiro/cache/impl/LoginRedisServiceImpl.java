/*
 * Copyright 2019-2029 geekidea(https://github.com/geekidea)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.geekidea.springbootplus.shiro.cache.impl;

import io.geekidea.springbootplus.common.constant.CommonRedisKey;
import io.geekidea.springbootplus.shiro.cache.LoginRedisService;
import io.geekidea.springbootplus.shiro.convert.ShiroMapstructConvert;
import io.geekidea.springbootplus.shiro.jwt.JwtProperties;
import io.geekidea.springbootplus.shiro.jwt.JwtToken;
import io.geekidea.springbootplus.shiro.vo.ClientInfo;
import io.geekidea.springbootplus.shiro.vo.JwtTokenRedisVo;
import io.geekidea.springbootplus.shiro.vo.LoginSysUserRedisVo;
import io.geekidea.springbootplus.shiro.vo.LoginSysUserVo;
import io.geekidea.springbootplus.system.convert.SysUserConvert;
import io.geekidea.springbootplus.util.ClientInfoUtil;
import io.geekidea.springbootplus.util.HttpServletRequestUtil;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.Set;

/**
 * 登陆信息Redis缓存服务类
 *
 * @author geekidea
 * @date 2019-09-30
 * @since 1.3.0.RELEASE
 **/
@Service
public class LoginRedisServiceImpl implements LoginRedisService {

    @Autowired
    private JwtProperties jwtProperties;

    @Autowired
    private RedisTemplate redisTemplate;

    /**
     * key-value: 有过期时间-->token过期时间
     * 1. tokenMd5:jwtTokenRedisVo
     * 2. username:loginSysUserRedisVo
     * 3. username:salt
     * hash: 没有过期时间，统计在线的用户信息
     * username:num
     */
    @Override
    public void cacheLoginInfo(JwtToken jwtToken, LoginSysUserVo loginSysUserVo, boolean generate) {
        if (jwtToken == null) {
            throw new IllegalArgumentException("jwtToken不能为空");
        }
        if (loginSysUserVo == null) {
            throw new IllegalArgumentException("loginSysUserVo不能为空");
        }
        // token
        String token = jwtToken.getToken();
        // 盐值
        String salt = jwtToken.getSalt();
        // 登陆用户名称
        String username = loginSysUserVo.getUsername();
        // token md5值
        String tokenMd5 = DigestUtils.md5Hex(token);

        // Redis缓存JWT Token信息
        JwtTokenRedisVo jwtTokenRedisVo = ShiroMapstructConvert.INSTANCE.jwtTokenToJwtTokenRedisVo(jwtToken);

        // 用户客户端信息
        ClientInfo clientInfo = ClientInfoUtil.get(HttpServletRequestUtil.getRequest());

        // Redis缓存登陆用户信息
        // 将LoginSysUserVo对象复制到LoginSysUserRedisVo，使用mapstruct进行对象属性复制
        LoginSysUserRedisVo loginSysUserRedisVo = SysUserConvert.INSTANCE.loginSysUserVoToLoginSysUserRedisVo(loginSysUserVo);
        loginSysUserRedisVo.setSalt(salt);
        loginSysUserRedisVo.setClientInfo(clientInfo);

        // Redis过期时间与JwtToken过期时间一致
        Duration expireDuration = Duration.ofSeconds(jwtToken.getExpireSecond());

        // 判断是否启用单个用户登陆，如果是，这每个用户只有一个有效token
        boolean singleLogin = jwtProperties.isSingleLogin();
        if (singleLogin) {
            deleteUserAllCache(username);
        }

        // 1. tokenMd5:jwtTokenRedisVo
        String loginTokenRedisKey = String.format(CommonRedisKey.LOGIN_TOKEN, tokenMd5);
        redisTemplate.opsForValue().set(loginTokenRedisKey, jwtTokenRedisVo, expireDuration);
        // 2. username:loginSysUserRedisVo
        redisTemplate.opsForValue().set(String.format(CommonRedisKey.LOGIN_USER, username), loginSysUserRedisVo, expireDuration);
        // 3. salt hash,方便获取盐值鉴权
        redisTemplate.opsForValue().set(String.format(CommonRedisKey.LOGIN_SALT, username), salt, expireDuration);
        // 4. login user token
        redisTemplate.opsForValue().set(String.format(CommonRedisKey.LOGIN_USER_TOKEN, username, tokenMd5), loginTokenRedisKey, expireDuration);
    }

    @Override
    public LoginSysUserRedisVo getLoginSysUserRedisVo(String username) {
        if (StringUtils.isBlank(username)) {
            throw new IllegalArgumentException("username不能为空");
        }
        return (LoginSysUserRedisVo) redisTemplate.opsForValue().get(String.format(CommonRedisKey.LOGIN_USER, username));
    }

    @Override
    public LoginSysUserVo getLoginSysUserVo(String username) {
        if (StringUtils.isBlank(username)) {
            throw new IllegalArgumentException("username不能为空");
        }
        LoginSysUserRedisVo userRedisVo = getLoginSysUserRedisVo(username);
        return userRedisVo;
    }

    @Override
    public String getSalt(String username) {
        if (StringUtils.isBlank(username)) {
            throw new IllegalArgumentException("username不能为空");
        }
        String salt = (String) redisTemplate.opsForValue().get(String.format(CommonRedisKey.LOGIN_SALT, username));
        return salt;
    }

    @Override
    public void deleteLoginInfo(String token, String username) {
        if (token == null) {
            throw new IllegalArgumentException("token不能为空");
        }
        if (username == null) {
            throw new IllegalArgumentException("username不能为空");
        }
        String tokenMd5 = DigestUtils.md5Hex(token);
        // 1. delete tokenMd5
        redisTemplate.delete(String.format(CommonRedisKey.LOGIN_TOKEN, tokenMd5));
        // 2. delete username
        redisTemplate.delete(String.format(CommonRedisKey.LOGIN_USER, username));
        // 3. delete salt
        redisTemplate.delete(String.format(CommonRedisKey.LOGIN_SALT, username));
        // 4. delete user token
        redisTemplate.delete(String.format(CommonRedisKey.LOGIN_USER_TOKEN, username, tokenMd5));
    }

    @Override
    public boolean exists(String token) {
        if (token == null) {
            throw new IllegalArgumentException("token不能为空");
        }
        String tokenMd5 = DigestUtils.md5Hex(token);
        Object object = redisTemplate.opsForValue().get(String.format(CommonRedisKey.LOGIN_TOKEN, tokenMd5));
        return object != null;
    }

    @Override
    public void deleteUserAllCache(String username) {
        Set<String> userTokenMd5Set = redisTemplate.keys(String.format(CommonRedisKey.LOGIN_USER_TOKEN, username, "*"));
        if (CollectionUtils.isEmpty(userTokenMd5Set)) {
            return;
        }

        // 1. 删除登陆用户的所有token信息
        List<String> tokenMd5List = redisTemplate.opsForValue().multiGet(userTokenMd5Set);
        redisTemplate.delete(tokenMd5List);
        // 2. 删除登陆用户的所有user:token信息
        redisTemplate.delete(userTokenMd5Set);
        // 3. 删除登陆用户信息
        redisTemplate.delete(String.format(CommonRedisKey.LOGIN_USER, username));
        // 4. 删除登陆用户盐值信息
        redisTemplate.delete(String.format(CommonRedisKey.LOGIN_SALT, username));
    }

}
