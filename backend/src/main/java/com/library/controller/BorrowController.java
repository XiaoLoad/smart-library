package com.library.controller;

import com.library.common.PageResult;
import com.library.common.Result;
import com.library.dto.*;
import com.library.entity.Borrow;
import com.library.service.BorrowService;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 借阅控制器
 */
@RestController
@RequestMapping("/borrow")
public class BorrowController {

    @Autowired
    private BorrowService borrowService;

    @GetMapping("/list")
    public Result<PageResult<Borrow>> list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) Integer status,
            @RequestParam(required = false) String search) {
        return Result.success(borrowService.list(page, size, status, search));
    }

    @PostMapping("/apply")
    public Result<Borrow> apply(@Valid @RequestBody BorrowApplyRequest req) {
        return Result.success("借阅申请已通过", borrowService.apply(req.getBookId()));
    }

    @PostMapping("/pickup")
    public Result<PickupResultVo> pickup(@RequestBody BorrowPickupRequest req) {
        List<String> nos = req.getBorrowNos();
        if (nos == null || nos.isEmpty()) {
            if (req.getBorrowNo() == null || req.getBorrowNo().isEmpty()) {
                return Result.error(400, "请提供借阅码");
            }
            nos = List.of(req.getBorrowNo());
        }
        return Result.success("取书成功", borrowService.pickup(nos));
    }

    @PostMapping("/return")
    public Result<Borrow> returnBook(@RequestBody BorrowReturnRequest req) {
        Borrow record = borrowService.returnBook(req.getId(), req.getBorrowNo());
        // 用户 → 归还申请已提交（status=5）；管理员 → 直接归还完成（status=2）
        String message = record.getStatus() == 5
                ? "归还申请已提交，请将图书送至服务台由管理员确认"
                : "归还成功";
        return Result.success(message, record);
    }

    @PostMapping("/return/confirm")
    public Result<Borrow> confirmReturn(@RequestBody BorrowReturnRequest req) {
        Borrow record = borrowService.confirmReturn(req.getId(), req.getBorrowNo());
        return Result.success("归还确认成功，库存已更新", record);
    }

    @PostMapping("/return/reject")
    public Result<Borrow> rejectReturn(@Valid @RequestBody BorrowReturnRejectRequest req) {
        Borrow record = borrowService.rejectReturn(req.getId(), req.getBorrowNo(), req.getReason());
        return Result.success("已驳回归还申请", record);
    }

    @PostMapping("/batch-return")
    public Result<PickupResultVo> batchReturn(@Valid @RequestBody BatchReturnRequest req) {
        return Result.success("批量归还完成", borrowService.batchReturn(req.getIds()));
    }

    @PostMapping("/renew")
    public Result<Borrow> renew(@RequestBody BorrowRenewRequest req) {
        return Result.success("续借成功", borrowService.renew(req.getId(), req.getBorrowNo()));
    }

    @GetMapping("/quota")
    public Result<BorrowQuotaVo> quota() {
        return Result.success(borrowService.quota());
    }
}
