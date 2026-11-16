package com.library.service;

import com.library.common.exception.BusinessException;
import com.library.dto.UserAddRequest;
import com.library.dto.UserProfileUpdateRequest;
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
        normalize(user);
        userMapper.insert(user);
        return user;
    }

    /**
     * 编辑用户
     * <p>
     * 管理员身份保护：不允许把管理员改为其他身份（无论是否最后一个管理员）。
     * 平台的写操作入口全部依赖管理员角色，把管理员降级后系统将无管理员可登录、
     * 无恢复入口（读者管理页本身需要管理员权限），属不可逆的锁定事故。
     * 需要降级时请直接改库或另建新管理员后由新管理员操作。
     */
    public User update(UserUpdateRequest req) {
        User user = userMapper.selectById(req.getId());
        if (user == null) {
            throw new BusinessException("用户不存在");
        }
        if (req.getUserType() != null && req.getUserType() != user.getUserType()) {
            if (user.getUserType() != null && user.getUserType() == 3) {
                throw new BusinessException(400, "管理员身份不可修改，无法将管理员降级为教师/学生");
            }
            user.setUserType(req.getUserType());
            user.setRoleName(ROLE_NAMES.getOrDefault(req.getUserType(), "未知"));
        }
        if (req.getRealName() != null) user.setRealName(req.getRealName());
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
        normalize(user);
        userMapper.update(user);
        return user;
    }

    /**
     * 读者自助修改本人联系方式。
     * <p>
     * 只写邮箱与手机：二者在 {@link UserProfileUpdateRequest} 上是必填，
     * 故无需处理 null；姓名、身份、学号/工号、状态与档案信息（院系/职称/班级/年级/专业）
     * 一律不在此处写入，由管理员在读者管理中维护。
     */
    public User updateProfile(Long userId, UserProfileUpdateRequest req) {
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new BusinessException(404, "用户不存在");
        }
        user.setEmail(req.getEmail().trim());
        user.setPhone(req.getPhone().trim());
        userMapper.update(user);
        return user;
    }

    /**
     * 规范化可空的唯一约束字段：空白字符串统一转为 NULL。
     * teacher_no / student_no 带 UNIQUE 索引，角色无关的字段本就为空；
     * MySQL 把 '' 当作具体值，多个空串会撞键（Duplicate entry ''），
     * 而 NULL 不受唯一约束限制，存 NULL 才能满足「唯一但允许多个空值」。
     */
    private void normalize(User user) {
        if (user.getTeacherNo() != null && user.getTeacherNo().isBlank()) {
            user.setTeacherNo(null);
        }
        if (user.getStudentNo() != null && user.getStudentNo().isBlank()) {
            user.setStudentNo(null);
        }
    }
}
