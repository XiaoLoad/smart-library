package com.library.service;

import com.library.common.exception.BusinessException;
import com.library.dto.UserAddRequest;
import com.library.dto.UserUpdateRequest;
import com.library.entity.User;
import com.library.mapper.UserMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * 用户服务
 */
@Service
public class UserService {

    @Autowired
    private UserMapper userMapper;

    @Autowired
    private BCryptPasswordEncoder passwordEncoder;

    private static final Map<Integer, String> ROLE_NAMES = Map.of(1, "教师", 2, "学生", 3, "管理员");

    /**
     * 新增用户
     */
    public User add(UserAddRequest req) {
        if (userMapper.selectByUsername(req.getUsername()) != null) {
            throw new BusinessException("用户名已存在");
        }
        User user = new User();
        user.setUsername(req.getUsername());
        user.setPassword(passwordEncoder.encode(req.getPassword()));
        user.setRealName(req.getRealName());
        user.setUserType(req.getUserType());
        user.setRoleName(ROLE_NAMES.getOrDefault(req.getUserType(), "未知"));
        user.setEmail(req.getEmail());
        user.setPhone(req.getPhone());
        user.setStatus(req.getStatus() != null ? req.getStatus() : 1);
        user.setAvatar("");
        // 教师字段
        user.setTeacherNo(req.getTeacherNo());
        user.setDepartment(req.getDepartment());
        user.setTitle(req.getTitle());
        // 学生字段
        user.setStudentNo(req.getStudentNo());
        user.setClassName(req.getClassName());
        user.setGrade(req.getGrade());
        user.setMajor(req.getMajor());
        userMapper.insert(user);
        return user;
    }

    /**
     * 编辑用户
     */
    public User update(UserUpdateRequest req) {
        User user = userMapper.selectById(req.getId());
        if (user == null) {
            throw new BusinessException("用户不存在");
        }
        if (req.getRealName() != null) user.setRealName(req.getRealName());
        if (req.getUserType() != null) {
            user.setUserType(req.getUserType());
            user.setRoleName(ROLE_NAMES.getOrDefault(req.getUserType(), "未知"));
        }
        if (req.getEmail() != null) user.setEmail(req.getEmail());
        if (req.getPhone() != null) user.setPhone(req.getPhone());
        if (req.getStatus() != null) user.setStatus(req.getStatus());
        user.setTeacherNo(req.getTeacherNo());
        user.setDepartment(req.getDepartment());
        user.setTitle(req.getTitle());
        user.setStudentNo(req.getStudentNo());
        user.setClassName(req.getClassName());
        user.setGrade(req.getGrade());
        user.setMajor(req.getMajor());
        userMapper.update(user);
        return user;
    }
}
