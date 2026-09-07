package com.library.controller;

import com.library.common.Result;
import com.library.dto.IdRequest;
import com.library.entity.Fine;
import com.library.mapper.FineMapper;
import com.library.security.CurrentUser;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 罚款控制器
 */
@RestController
@RequestMapping("/fine")
public class FineController {

    @Autowired
    private FineMapper fineMapper;

    @GetMapping("/list")
    public Result<Map<String, Object>> list() {
        CurrentUser cu = CurrentUser.get();
        boolean isAdmin = cu.getUserType() == 3;
        List<Fine> list = fineMapper.selectList(cu.getUserId(), isAdmin);
        // 返回浅拷贝避免前端引用比对问题
        return Result.success(Map.of("list", list, "total", list.size()));
    }

    @PostMapping("/pay")
    public Result<Void> pay(@RequestBody IdRequest req) {
        Fine fine = fineMapper.selectById(req.getId());
        if (fine == null) {
            return Result.error(400, "罚款记录不存在");
        }
        CurrentUser cu = CurrentUser.get();
        if (cu.getUserType() != 3 && !fine.getUserId().equals(cu.getUserId())) {
            return Result.error(403, "只能缴纳本人罚款");
        }
        if (fine.getStatus() == 1) {
            return Result.error(400, "该罚款已缴纳");
        }
        fineMapper.updatePaid(fine.getId(), LocalDateTime.now(), 1);
        return Result.success("缴费成功", null);
    }
}
