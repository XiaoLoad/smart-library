package com.library.controller;

import com.github.pagehelper.PageHelper;
import com.github.pagehelper.PageInfo;
import com.library.common.PageResult;
import com.library.common.Result;
import com.library.dto.UserAddRequest;
import com.library.dto.UserProfileUpdateRequest;
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
        if (!isAdmin(CurrentUser.get())) {
            return Result.error(403, "仅管理员可查看读者名录");
        }
        PageHelper.startPage(page, size);
        List<User> list = userMapper.selectList(keyword);
        // 脱敏：名录只需身份与联系方式，密码哈希不出后端
        list.forEach(u -> u.setPassword(null));
        PageInfo<User> info = new PageInfo<>(list);
        return Result.success(new PageResult<>(info.getList(), info.getTotal()));
    }

    @GetMapping("/detail")
    public Result<User> detail(@RequestParam Long id) {
        if (!isAdmin(CurrentUser.get())) {
            return Result.error(403, "仅管理员可查看读者详情");
        }
        User user = userMapper.selectById(id);
        if (user == null) {
            return Result.error(404, "用户不存在");
        }
        // 脱敏：不返回密码
        user.setPassword(null);
        return Result.success(user);
    }

    // 本人档案（自助）

    /**
     * 查看本人完整档案。与管理员的 /user/detail 不同，此处恒取 token 里的本人 id，
     * 不接受任何入参，读者拿不到别人的记录。
     */
    @GetMapping("/profile")
    public Result<User> profile() {
        User user = userMapper.selectById(CurrentUser.get().getUserId());
        if (user == null) {
            return Result.error(404, "用户不存在");
        }
        user.setPassword(null);
        return Result.success(user);
    }

    /**
     * 修改本人联系方式（写操作）。档案信息不在可改范围，由管理员在读者管理中维护。
     */
    @PostMapping("/profile/update")
    public Result<User> updateProfile(@Valid @RequestBody UserProfileUpdateRequest req) {
        User user = userService.updateProfile(CurrentUser.get().getUserId(), req);
        user.setPassword(null);
        return Result.success("保存成功", user);
    }

    @PostMapping("/add")
    public Result<User> add(@Valid @RequestBody UserAddRequest req) {
        if (!isAdmin(CurrentUser.get())) {
            return Result.error(403, "仅管理员可新增读者");
        }
        User user = userService.add(req);
        user.setPassword(null);
        return Result.success("新增成功", user);
    }

    @PostMapping("/update")
    public Result<User> update(@Valid @RequestBody UserUpdateRequest req) {
        if (!isAdmin(CurrentUser.get())) {
            return Result.error(403, "仅管理员可编辑读者");
        }
        User user = userService.update(req);
        user.setPassword(null);
        return Result.success("更新成功", user);
    }

    /** 读者名录相关操作一律限管理员；token 里缺 userType 按无权限处理 */
    private boolean isAdmin(CurrentUser cu) {
        return cu != null && cu.getUserType() != null && cu.getUserType() == 3;
    }
}
