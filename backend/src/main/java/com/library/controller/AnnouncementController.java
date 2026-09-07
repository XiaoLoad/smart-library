package com.library.controller;

import com.github.pagehelper.PageHelper;
import com.github.pagehelper.PageInfo;
import com.library.common.PageResult;
import com.library.common.Result;
import com.library.dto.AnnouncementAddRequest;
import com.library.dto.IdRequest;
import com.library.entity.Announcement;
import com.library.mapper.AnnouncementMapper;
import com.library.mapper.UserMapper;
import com.library.security.CurrentUser;
import com.library.entity.User;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 公告控制器
 */
@RestController
@RequestMapping("/announcement")
public class AnnouncementController {

    @Autowired
    private AnnouncementMapper announcementMapper;

    @Autowired
    private UserMapper userMapper;

    @GetMapping("/list")
    public Result<PageResult<Announcement>> list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size) {
        PageHelper.startPage(page, size);
        List<Announcement> list = announcementMapper.selectList();
        PageInfo<Announcement> info = new PageInfo<>(list);
        return Result.success(new PageResult<>(info.getList(), info.getTotal()));
    }

    @GetMapping("/detail")
    public Result<Announcement> detail(@RequestParam Long id) {
        Announcement a = announcementMapper.selectById(id);
        if (a == null) {
            return Result.error(400, "公告不存在");
        }
        return Result.success(a);
    }

    @PostMapping("/add")
    public Result<Announcement> add(@Valid @RequestBody AnnouncementAddRequest req) {
        CurrentUser cu = CurrentUser.get();
        if (cu.getUserType() != 3) {
            return Result.error(403, "仅管理员可发布公告");
        }
        User user = userMapper.selectById(cu.getUserId());
        Announcement a = new Announcement();
        a.setTitle(req.getTitle());
        a.setContent(req.getContent());
        a.setPublisherId(cu.getUserId());
        a.setPublisherName(user.getRealName());
        a.setTop(Boolean.TRUE.equals(req.getTop()) ? 1 : 0);
        a.setStatus(1);
        announcementMapper.insert(a);
        return Result.success("发布成功", a);
    }

    @PostMapping("/delete")
    public Result<Void> delete(@RequestBody IdRequest req) {
        CurrentUser cu = CurrentUser.get();
        if (cu.getUserType() != 3) {
            return Result.error(403, "仅管理员可删除公告");
        }
        announcementMapper.deleteById(req.getId());
        return Result.success("删除成功", null);
    }

    @PostMapping("/toggle-top")
    public Result<Announcement> toggleTop(@RequestBody IdRequest req) {
        CurrentUser cu = CurrentUser.get();
        if (cu.getUserType() != 3) {
            return Result.error(403, "仅管理员可设置置顶");
        }
        Announcement a = announcementMapper.selectById(req.getId());
        if (a == null) {
            return Result.error(400, "公告不存在");
        }
        int newTop = a.getTop() != null && a.getTop() == 1 ? 0 : 1;
        announcementMapper.updateTop(a.getId(), newTop);
        a.setTop(newTop);
        return Result.success(newTop == 1 ? "已置顶" : "已取消置顶", a);
    }
}
