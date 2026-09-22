package com.library.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.library.common.exception.BusinessException;
import com.library.dto.LoginResponse;
import com.library.dto.UserVo;
import com.library.entity.User;
import com.library.entity.WechatAccount;
import com.library.mapper.UserMapper;
import com.library.mapper.WechatAccountMapper;
import com.library.security.JwtUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

/**
 * 微信小程序登录服务：wx.login code → code2session → openid 绑定/登录。
 * 流程约定：首次用账号密码登录（/auth/login），再绑定微信；之后可微信一键登录。
 */
@Service
public class WechatAuthService {

    private static final Logger log = LoggerFactory.getLogger(WechatAuthService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newHttpClient();

    @Autowired
    private WechatAccountMapper wechatAccountMapper;
    @Autowired
    private UserMapper userMapper;
    @Autowired
    private JwtUtil jwtUtil;

    @Value("${wechat.appid:}")
    private String appid;
    @Value("${wechat.secret:}")
    private String secret;

    /** code2session 结果 */
    public static class OpenidResult {
        public String openid;
        public String unionid;
    }

    /** code 换 openid */
    public OpenidResult code2session(String code) {
        if (appid == null || appid.isBlank() || secret == null || secret.isBlank()) {
            throw new BusinessException("微信登录未配置（wechat.appid / wechat.secret）");
        }
        String url = "https://api.weixin.qq.com/sns/jscode2session?appid=" + appid
                + "&secret=" + secret
                + "&js_code=" + code
                + "&grant_type=authorization_code";
        try {
            HttpRequest req = HttpRequest.newBuilder().uri(URI.create(url)).GET().build();
            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            JsonNode node = MAPPER.readTree(resp.body());
            int errcode = node.path("errcode").asInt(0);
            if (errcode != 0) {
                log.warn("wechat code2session failed: {}", resp.body());
                throw new BusinessException("微信登录失败：" + node.path("errmsg").asText("code 无效或已过期"));
            }
            OpenidResult r = new OpenidResult();
            r.openid = node.path("openid").asText(null);
            r.unionid = node.path("unionid").asText(null);
            if (r.openid == null || r.openid.isBlank()) {
                throw new BusinessException("微信登录失败：未返回 openid");
            }
            return r;
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("wechat code2session error", e);
            throw new BusinessException("微信服务请求失败，请稍后重试");
        }
    }

    /** 微信一键登录：仅已绑定用户可用，未绑定引导先用账号密码登录 */
    public LoginResponse login(String code) {
        OpenidResult wx = code2session(code);
        WechatAccount account = wechatAccountMapper.selectByOpenid(wx.openid);
        if (account == null) {
            throw new BusinessException(403, "该微信尚未绑定账号，请先使用账号密码登录");
        }
        User user = userMapper.selectById(account.getUserId());
        if (user == null || user.getStatus() != 1) {
            throw new BusinessException(401, "账号不存在或已被禁用");
        }
        return buildLoginResponse(user);
    }

    /** 绑定微信到当前登录用户（同一微信仅可绑定一个账号） */
    @Transactional
    public void bind(Long userId, String code) {
        OpenidResult wx = code2session(code);
        WechatAccount existing = wechatAccountMapper.selectByOpenid(wx.openid);
        if (existing != null) {
            if (!existing.getUserId().equals(userId)) {
                throw new BusinessException("该微信已绑定其他账号");
            }
            return; // 已绑定本人，幂等成功
        }
        WechatAccount account = new WechatAccount();
        account.setUserId(userId);
        account.setOpenid(wx.openid);
        account.setUnionid(wx.unionid);
        wechatAccountMapper.insert(account);
    }

    @Transactional
    public void unbind(Long userId) {
        wechatAccountMapper.deleteByUserId(userId);
    }

    public boolean isBound(Long userId) {
        return wechatAccountMapper.selectByUserId(userId) != null;
    }

    private LoginResponse buildLoginResponse(User user) {
        String token = jwtUtil.generate(user.getId(), user.getUsername(), user.getUserType());
        UserVo vo = UserVo.builder()
                .id(user.getId())
                .username(user.getUsername())
                .realName(user.getRealName())
                .userType(user.getUserType())
                .roleName(user.getRoleName())
                .avatar(user.getAvatar())
                .build();
        return new LoginResponse(token, vo);
    }
}
