package com.library.service;

import com.github.pagehelper.PageHelper;
import com.github.pagehelper.PageInfo;
import com.library.common.PageResult;
import com.library.common.exception.BusinessException;
import com.library.dto.BorrowQuotaVo;
import com.library.dto.PickupResultVo;
import com.library.entity.*;
import com.library.mapper.*;
import com.library.security.CurrentUser;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * 借阅服务（核心业务）
 */
@Service
public class BorrowService {

    /** 逾期罚款费率（元/天，按自然日计算） */
    private static final java.math.BigDecimal FINE_RATE_PER_DAY = new java.math.BigDecimal("0.50");

    @Autowired
    private BorrowMapper borrowMapper;
    @Autowired
    private BorrowLimitMapper borrowLimitMapper;
    @Autowired
    private UserMapper userMapper;
    @Autowired
    private BookMapper bookMapper;
    @Autowired
    private FineMapper fineMapper;

    /**
     * 同步借阅状态
     * 待取书超期 → 已取消 + 恢复库存
     * 借阅中超期 → 已逾期
     */
    @Transactional
    public void syncBorrowStatus() {
        LocalDateTime now = LocalDateTime.now();
        // 待取书超期 → 已取消（恢复库存）
        List<Borrow> overduePickups = borrowMapper.selectList(null, true, 0, null);
        for (Borrow b : overduePickups) {
            if (b.getPickupDeadline() != null && b.getPickupDeadline().isBefore(now)) {
                borrowMapper.updateStatus(b.getId(), 4, "取书超期已自动取消");
                bookMapper.increaseAvailable(b.getBookId(), 1);
            }
        }
        // 借阅中超期 → 已逾期
        List<Borrow> borrowing = borrowMapper.selectList(null, true, 1, null);
        for (Borrow b : borrowing) {
            if (b.getDueTime() != null && b.getDueTime().isBefore(now)) {
                borrowMapper.updateStatus(b.getId(), 3, "已逾期");
            }
        }
    }

    /**
     * 借阅列表
     */
    public PageResult<Borrow> list(int page, int size, Integer status, String search) {
        syncBorrowStatus();
        CurrentUser cu = CurrentUser.get();
        boolean isAdmin = cu.getUserType() == 3;
        PageHelper.startPage(page, size);
        List<Borrow> list = borrowMapper.selectList(cu.getUserId(), isAdmin, status, search);
        PageInfo<Borrow> info = new PageInfo<>(list);
        return new PageResult<>(info.getList(), info.getTotal());
    }

    /**
     * 借阅申请
     */
    @Transactional
    public Borrow apply(Long bookId) {
        CurrentUser cu = CurrentUser.get();
        User user = userMapper.selectById(cu.getUserId());
        if (user == null || user.getStatus() != 1) {
            throw new BusinessException(403, "用户不存在或已禁用");
        }
        BorrowLimit limit = borrowLimitMapper.selectByType(user.getUserType());
        if (limit == null) {
            throw new BusinessException(403, "管理员账号不可借阅");
        }
        // 额度校验（待取书 + 借阅中 + 待确认归还，书仍在借阅人手上）
        int used = borrowMapper.countByUserAndStatuses(user.getId(), List.of(0, 1, 5));
        if (used >= limit.getMaxBooks()) {
            throw new BusinessException(400, "借阅额度已满（" + limit.getMaxBooks() + "本），请先归还");
        }
        // 逾期校验（含申请归还但已超期的记录）
        int overdueCount = borrowMapper.countOverduePending(user.getId());
        if (overdueCount > 0) {
            throw new BusinessException(400, "您有逾期未还图书，请先归还");
        }
        // 图书校验
        Book book = bookMapper.selectById(bookId);
        if (book == null) {
            throw new BusinessException(400, "图书不存在");
        }
        if (book.getAvailableCount() <= 0) {
            throw new BusinessException(400, "该图书暂无库存");
        }

        // 生成借阅码 BR{YYYYMMDD}{seq4}
        String today = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        String prefix = "BR" + today;
        Integer maxSeq = borrowMapper.selectMaxSeqOfDay(prefix);
        int seq = (maxSeq == null ? 0 : maxSeq) + 1;
        String borrowNo = prefix + String.format("%04d", seq);

        LocalDateTime now = LocalDateTime.now();
        Borrow record = new Borrow();
        record.setBorrowNo(borrowNo);
        record.setUserId(user.getId());
        record.setUserName(user.getRealName());
        record.setBookId(book.getId());
        record.setBookTitle(book.getTitle());
        record.setApplyTime(now);
        record.setRenewCount(0);
        record.setRenewMaxCount(limit.getRenewMaxCount());
        record.setPickupDeadline(now.plusDays(limit.getPickupDays()));
        record.setStatus(0);
        record.setRemark("待取书");
        // 生成二维码 payload
        String qrPayload = String.format(
                "{\"borrowNo\":\"%s\",\"userId\":%d,\"bookId\":%d,\"bookTitle\":\"%s\"}",
                borrowNo, user.getId(), book.getId(), book.getTitle()
        );
        record.setQrPayload(qrPayload);

        borrowMapper.insert(record);
        bookMapper.decreaseAvailable(book.getId(), 1);
        return record;
    }

    /**
     * 取书确认（管理员，支持批量，供借阅码弹窗等场景）
     */
    @Transactional
    public PickupResultVo pickup(List<String> borrowNos) {
        CurrentUser cu = CurrentUser.get();
        if (cu.getUserType() != 3) {
            throw new BusinessException(403, "仅管理员可取书确认");
        }
        List<Map<String, Object>> results = new ArrayList<>();
        int success = 0;
        for (String no : borrowNos) {
            Map<String, Object> r = new HashMap<>();
            r.put("borrowNo", no);
            try {
                Borrow record = borrowMapper.selectByBorrowNo(no);
                if (record == null) {
                    throw new BusinessException("借阅码不存在");
                }
                doPickup(record, cu);
                r.put("success", true);
                r.put("record", record);
                success++;
            } catch (BusinessException e) {
                r.put("success", false);
                r.put("message", e.getMessage());
            }
        }
        int fail = borrowNos.size() - success;
        return new PickupResultVo(results, success, fail);
    }

    /**
     * 执行取书：0 → 1，按借阅人类型计算应还时间
     */
    private void doPickup(Borrow record, CurrentUser cu) {
        if (record.getStatus() != 0) {
            throw new BusinessException("该记录非待取书状态");
        }
        User borrower = userMapper.selectById(record.getUserId());
        BorrowLimit limit = borrower == null ? null : borrowLimitMapper.selectByType(borrower.getUserType());
        if (limit == null) {
            throw new BusinessException("无法获取借阅额度");
        }
        LocalDateTime now = LocalDateTime.now();
        borrowMapper.updatePickup(
                record.getId(), 1, now,
                now.plusDays(limit.getLoanDays()), "",
                cu.getUserId()
        );
    }

    /**
     * 归还入口（按角色分流）
     * 用户：发起归还申请 1/3 → 5（待确认归还），库存不变，需管理员确认
     * 管理员：直接归还 1/3 → 2（柜台现场收书场景），库存 +1
     */
    @Transactional
    public Borrow returnBook(Long id, String borrowNo) {
        Borrow record = findRecord(id, borrowNo);
        // 非管理员只能操作本人记录
        CurrentUser cu = CurrentUser.get();
        if (cu.getUserType() != 3 && !record.getUserId().equals(cu.getUserId())) {
            throw new BusinessException(403, "只能归还本人借阅的图书");
        }
        if (record.getStatus() != 1 && record.getStatus() != 3) {
            throw new BusinessException("该记录状态不可归还");
        }
        LocalDateTime now = LocalDateTime.now();
        if (cu.getUserType() == 3) {
            // 管理员直接归还，罚款按当前时间结算
            return completeReturn(record, now, cu.getUserId(), "已归还（管理员确认）");
        }
        // 用户发起归还申请，进入待确认归还
        borrowMapper.updateReturnRequest(record.getId(), now, "待确认归还");
        record.setStatus(5);
        record.setReturnRequestTime(now);
        record.setRemark("待确认归还");
        return record;
    }

    /**
     * 归还确认（管理员）：实物核对后完成归还 5 → 2
     * 逾期罚款以「用户申请归还时间」为基准计算
     */
    @Transactional
    public Borrow confirmReturn(Long id, String borrowNo) {
        CurrentUser cu = CurrentUser.get();
        if (cu.getUserType() != 3) {
            throw new BusinessException(403, "仅管理员可确认归还");
        }
        Borrow record = findRecord(id, borrowNo);
        if (record.getStatus() != 5) {
            throw new BusinessException("该记录非待确认归还状态");
        }
        LocalDateTime effective = record.getReturnRequestTime() != null
                ? record.getReturnRequestTime() : LocalDateTime.now();
        return completeReturn(record, effective, cu.getUserId(), "已归还");
    }

    /**
     * 驳回归还申请（管理员）：未收到书 / 图书污损等
     * 状态按是否超期回落到 1 借阅中 / 3 已逾期，逾期继续计算
     */
    @Transactional
    public Borrow rejectReturn(Long id, String borrowNo, String reason) {
        CurrentUser cu = CurrentUser.get();
        if (cu.getUserType() != 3) {
            throw new BusinessException(403, "仅管理员可驳回归还申请");
        }
        Borrow record = findRecord(id, borrowNo);
        if (record.getStatus() != 5) {
            throw new BusinessException("该记录非待确认归还状态");
        }
        LocalDateTime now = LocalDateTime.now();
        boolean overdue = record.getDueTime() != null && record.getDueTime().isBefore(now);
        int backStatus = overdue ? 3 : 1;
        String remark = "归还申请被驳回" + (reason == null || reason.isBlank() ? "" : "：" + reason);
        borrowMapper.updateReturnReject(record.getId(), backStatus, remark);
        record.setStatus(backStatus);
        record.setReturnRequestTime(null);
        record.setRemark(remark);
        return record;
    }

    /**
     * 完成归还（库存 +1 + 按生效时间结算逾期罚款）
     *
     * @param effectiveTime 罚款结算基准时间：用户申请归还时间 / 管理员直接归还时间
     */
    private Borrow completeReturn(Borrow record, LocalDateTime effectiveTime, Long adminId, String remark) {
        // 逾期罚款：应还日之后按自然日计费
        if (record.getDueTime() != null && effectiveTime.isAfter(record.getDueTime())) {
            long days = java.time.temporal.ChronoUnit.DAYS.between(
                    record.getDueTime().toLocalDate(), effectiveTime.toLocalDate());
            if (days > 0) {
                Fine fine = new Fine();
                fine.setUserId(record.getUserId());
                fine.setUserName(record.getUserName());
                fine.setBorrowRecordId(record.getId());
                fine.setBorrowNo(record.getBorrowNo());
                fine.setBookTitle(record.getBookTitle());
                fine.setDaysOverdue((int) days);
                fine.setAmount(FINE_RATE_PER_DAY.multiply(java.math.BigDecimal.valueOf(days)));
                fine.setReason("逾期归还");
                fine.setStatus(0);
                fineMapper.insert(fine);
                remark = remark + "，逾期罚款已生成";
            }
        }
        LocalDateTime now = LocalDateTime.now();
        borrowMapper.updateReturn(record.getId(), now, 2, remark, adminId);
        bookMapper.increaseAvailable(record.getBookId(), 1);
        record.setStatus(2);
        record.setReturnTime(now);
        record.setRemark(remark);
        return record;
    }

    /**
     * 批量办理（管理员，柜台统一事务入口）
     * 取书 / 归还 / 归还确认本质是同一笔柜台事务，仅目的不同，
     * 按记录状态自动分流：0 待取书 → 取书；1/3 → 直接归还；5 → 归还确认
     */
    @Transactional
    public PickupResultVo batchProcess(List<Long> ids, List<String> borrowNos) {
        CurrentUser cu = CurrentUser.get();
        if (cu.getUserType() != 3) {
            throw new BusinessException(403, "仅管理员可批量办理");
        }
        List<Long> idList = ids == null ? Collections.emptyList() : ids;
        List<String> noList = borrowNos == null ? Collections.emptyList() : borrowNos;
        List<Map<String, Object>> results = new ArrayList<>(idList.size() + noList.size());
        int success = 0;
        for (Long id : idList) {
            Map<String, Object> r = new HashMap<>();
            r.put("requestId", id);
            success += processOne(r, id, null, cu);
            results.add(r);
        }
        for (String no : noList) {
            Map<String, Object> r = new HashMap<>();
            r.put("requestBorrowNo", no);
            success += processOne(r, null, no, cu);
            results.add(r);
        }
        return new PickupResultVo(results, success, idList.size() + noList.size() - success);
    }

    /**
     * 按状态分流办理单条记录
     * @return 1 成功 / 0 失败（失败原因写入 result.message）
     */
    private int processOne(Map<String, Object> result, Long id, String borrowNo, CurrentUser cu) {
        try {
            Borrow record = findRecord(id, borrowNo);
            result.put("id", record.getId());
            result.put("borrowNo", record.getBorrowNo());
            result.put("bookTitle", record.getBookTitle());
            result.put("userName", record.getUserName());
            String action;
            switch (record.getStatus()) {
                case 0 -> {
                    doPickup(record, cu);
                    action = "pickup";
                }
                case 1, 3 -> {
                    completeReturn(record, LocalDateTime.now(), cu.getUserId(), "已归还（柜台办理）");
                    action = "return";
                }
                case 5 -> {
                    LocalDateTime effective = record.getReturnRequestTime() != null
                            ? record.getReturnRequestTime() : LocalDateTime.now();
                    completeReturn(record, effective, cu.getUserId(), "已归还");
                    action = "confirm";
                }
                default -> throw new BusinessException("该状态无需柜台办理");
            }
            result.put("success", true);
            result.put("action", action);
            return 1;
        } catch (BusinessException e) {
            result.put("success", false);
            result.put("message", e.getMessage());
            return 0;
        }
    }

    /**
     * 续借
     */
    @Transactional
    public Borrow renew(Long id, String borrowNo) {
        Borrow record;
        if (id != null) {
            record = borrowMapper.selectById(id);
        } else if (borrowNo != null) {
            record = borrowMapper.selectByBorrowNo(borrowNo);
        } else {
            throw new BusinessException("请提供借阅记录ID或借阅码");
        }
        if (record == null) {
            throw new BusinessException("借阅记录不存在");
        }
        if (record.getStatus() != 1) {
            throw new BusinessException("只有借阅中的记录可续借");
        }
        if (record.getRenewCount() >= record.getRenewMaxCount()) {
            throw new BusinessException("续借次数已用完");
        }
        // 有其他逾期不可续借（含申请归还但已超期的记录）
        int overdueCount = borrowMapper.countOverduePending(record.getUserId());
        if (overdueCount > 0) {
            throw new BusinessException("您有逾期未还图书，请先归还");
        }
        // 获取额度规则
        User user = userMapper.selectById(record.getUserId());
        BorrowLimit limit = borrowLimitMapper.selectByType(user.getUserType());
        LocalDateTime newDueTime = record.getDueTime().plusDays(limit.getRenewDays());
        borrowMapper.updateRenew(record.getId(), newDueTime, record.getRenewCount() + 1);
        record.setDueTime(newDueTime);
        record.setRenewCount(record.getRenewCount() + 1);
        return record;
    }

    /**
     * 借阅额度
     */
    public BorrowQuotaVo quota() {
        syncBorrowStatus();
        CurrentUser cu = CurrentUser.get();
        User user = userMapper.selectById(cu.getUserId());
        BorrowLimit limit = borrowLimitMapper.selectByType(user.getUserType());
        BorrowQuotaVo vo = new BorrowQuotaVo();
        if (limit == null) {
            vo.setMaxBooks(0);
            vo.setUsed(0);
            vo.setRemaining(0);
            vo.setOverdueCount(0);
            vo.setCanBorrow(false);
            return vo;
        }
        int used = borrowMapper.countByUserAndStatuses(user.getId(), List.of(0, 1, 5));
        int overdueCount = borrowMapper.countOverduePending(user.getId());
        vo.setMaxBooks(limit.getMaxBooks());
        vo.setUsed(used);
        vo.setRemaining(Math.max(0, limit.getMaxBooks() - used));
        vo.setOverdueCount(overdueCount);
        vo.setCanBorrow(used < limit.getMaxBooks() && overdueCount == 0);
        return vo;
    }

    /**
     * 按记录 ID 或借阅码查找借阅记录
     */
    private Borrow findRecord(Long id, String borrowNo) {
        Borrow record;
        if (id != null) {
            record = borrowMapper.selectById(id);
        } else if (borrowNo != null && !borrowNo.isBlank()) {
            record = borrowMapper.selectByBorrowNo(borrowNo);
        } else {
            throw new BusinessException("请提供借阅记录ID或借阅码");
        }
        if (record == null) {
            throw new BusinessException("借阅记录不存在");
        }
        return record;
    }
}
