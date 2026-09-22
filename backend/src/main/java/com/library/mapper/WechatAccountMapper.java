package com.library.mapper;

import com.library.entity.WechatAccount;
import org.apache.ibatis.annotations.Param;

public interface WechatAccountMapper {

    WechatAccount selectByOpenid(@Param("openid") String openid);

    WechatAccount selectByUserId(@Param("userId") Long userId);

    void insert(WechatAccount account);

    void deleteByUserId(@Param("userId") Long userId);
}
