package com.library.controller;

import com.github.pagehelper.PageHelper;
import com.github.pagehelper.PageInfo;
import com.library.common.PageResult;
import com.library.common.Result;
import com.library.dto.UserAddRequest;
import com.library.dto.UserUpdateRequest;
import com.library.dto.UserVo;
import com.library.entity.User;
import com.library.mapper.UserMapper;
import com.library.security.CurrentUser;
import com.library.service.UserService;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 用户控制器
 */
@RestController
@RequestMapping("/user")
public class UserController {

    @Autowired
    private UserMapper userMapper;

    @Autowired
    private UserService userService;

    @GetMapping("/info")
    public Result<Map<String, Object>> info() {
        CurrentUser cu = CurrentUser.get();
        User user = userMapper.selectById(cu.getUserId());
        if (user == null) {
            return Result.error(404, "用户不存在");
        }
        UserVo vo = UserVo.builder()
                .id(user.getId())
                .username(user.getUsername())
                .realName(user.getRealName())
                .userType(user.getUserType())
                .roleName(user.getRoleName())
                .avatar(user.getAvatar())
                .build();
        Map<String, Object> data = new HashMap<>();
        data.put("user", vo);
        return Result.success(data);
    }

    @GetMapping("/list")
    public Result<PageResult<User>> list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) String keyword) {
        PageHelper.startPage(page, size);
        List<User> list = userMapper.selectList(keyword);
        PageInfo<User> info = new PageInfo<>(list);
        return Result.success(new PageResult<>(info.getList(), info.getTotal()));
    }

    @GetMapping("/detail")
    public Result<User> detail(@RequestParam Long id) {
        User user = userMapper.selectById(id);
        if (user == null) {
            return Result.error(404, "用户不存在");
        }
        // 脱敏：不返回密码
        user.setPassword(null);
        return Result.success(user);
    }

    @PostMapping("/add")
    public Result<User> add(@Valid @RequestBody UserAddRequest req) {
        CurrentUser cu = CurrentUser.get();
        if (cu.getUserType() != 3) {
            return Result.error(403, "仅管理员可新增读者");
        }
        User user = userService.add(req);
        user.setPassword(null);
        return Result.success("新增成功", user);
    }

    @PostMapping("/update")
    public Result<User> update(@Valid @RequestBody UserUpdateRequest req) {
        CurrentUser cu = CurrentUser.get();
        if (cu.getUserType() != 3) {
            return Result.error(403, "仅管理员可编辑读者");
        }
        User user = userService.update(req);
        user.setPassword(null);
        return Result.success("更新成功", user);
    }
}
