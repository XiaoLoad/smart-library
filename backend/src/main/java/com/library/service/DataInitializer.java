package com.library.service;

import com.library.entity.User;
import com.library.mapper.UserMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 数据初始化器：首启时编码明文密码为 BCrypt
 */
@Slf4j
@Component
public class DataInitializer implements CommandLineRunner {

    @Autowired
    private UserMapper userMapper;

    @Autowired
    private BCryptPasswordEncoder passwordEncoder;

    @Override
    public void run(String... args) {
        List<User> all = userMapper.selectAll();
        int count = 0;
        for (User u : all) {
            String p = u.getPassword();
            if (p == null || (!p.startsWith("$2a") && !p.startsWith("$2b") && !p.startsWith("$2y"))) {
                String encoded = passwordEncoder.encode(p);
                userMapper.updatePassword(u.getId(), encoded);
                count++;
            }
        }
        if (count > 0) {
            log.info("DataInitializer: 已将 {} 个用户的明文密码编码为 BCrypt", count);
        }
    }
}
