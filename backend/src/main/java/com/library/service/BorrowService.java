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

    @Autowired
    private BorrowMapper borrowMapper;
    @Autowired
    private BorrowLimitMapper borrowLimitMapper;
    @Autowired
    private UserMapper userMapper;
    @Autowired
    private BookMapper bookMapper;

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
        // 额度校验（待取书 + 借阅中）
        int used = borrowMapper.countByUserAndStatuses(user.getId(), List.of(0, 1));
        if (used >= limit.getMaxBooks()) {
            throw new BusinessException(400, "借阅额度已满（" + limit.getMaxBooks() + "本），请先归还");
        }
        // 逾期校验
        int overdueCount = borrowMapper.countByUserAndStatuses(user.getId(), List.of(3));
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
     * 取书确认（管理员，批量）
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
                if (record.getStatus() != 0) {
                    throw new BusinessException("该记录非待取书状态");
                }
                User borrower = userMapper.selectById(record.getUserId());
                BorrowLimit limit = borrowLimitMapper.selectByType(borrower.getUserType());
                if (limit == null) {
                    throw new BusinessException("无法获取借阅额度");
                }
                LocalDateTime now = LocalDateTime.now();
                borrowMapper.updatePickup(
                        record.getId(), 1, now,
                        now.plusDays(limit.getLoanDays()), ""
                );
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
     * 归还
     */
    @Transactional
    public Borrow returnBook(Long id, String borrowNo) {
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
        // 非管理员只能归还本人
        CurrentUser cu = CurrentUser.get();
        if (cu.getUserType() != 3 && !record.getUserId().equals(cu.getUserId())) {
            throw new BusinessException(403, "只能归还本人借阅的图书");
        }
        if (record.getStatus() != 1 && record.getStatus() != 3) {
            throw new BusinessException("该记录状态不可归还");
        }
        borrowMapper.updateReturn(record.getId(), LocalDateTime.now(), 2, "已归还");
        bookMapper.increaseAvailable(record.getBookId(), 1);
        record.setStatus(2);
        record.setReturnTime(LocalDateTime.now());
        return record;
    }

    /**
     * 批量归还
     */
    @Transactional
    public PickupResultVo batchReturn(List<Long> ids) {
        CurrentUser cu = CurrentUser.get();
        List<Map<String, Object>> results = new ArrayList<>();
        int success = 0;
        for (Long id : ids) {
            Map<String, Object> r = new HashMap<>();
            r.put("id", id);
            try {
                Borrow record = borrowMapper.selectById(id);
                if (record == null) {
                    throw new BusinessException("借阅记录不存在");
                }
                // 非管理员只能归还本人
                if (cu.getUserType() != 3 && !record.getUserId().equals(cu.getUserId())) {
                    throw new BusinessException(403, "只能归还本人借阅的图书");
                }
                if (record.getStatus() != 1 && record.getStatus() != 3) {
                    throw new BusinessException("该记录状态不可归还");
                }
                borrowMapper.updateReturn(record.getId(), LocalDateTime.now(), 2, "已归还");
                bookMapper.increaseAvailable(record.getBookId(), 1);
                r.put("success", true);
                r.put("record", record);
                success++;
            } catch (BusinessException e) {
                r.put("success", false);
                r.put("message", e.getMessage());
            }
        }
        int fail = ids.size() - success;
        return new PickupResultVo(results, success, fail);
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
        // 有其他逾期不可续借
        int overdueCount = borrowMapper.countByUserAndStatuses(record.getUserId(), List.of(3));
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
        int used = borrowMapper.countByUserAndStatuses(user.getId(), List.of(0, 1));
        int overdueCount = borrowMapper.countByUserAndStatuses(user.getId(), List.of(3));
        vo.setMaxBooks(limit.getMaxBooks());
        vo.setUsed(used);
        vo.setRemaining(Math.max(0, limit.getMaxBooks() - used));
        vo.setOverdueCount(overdueCount);
        vo.setCanBorrow(used < limit.getMaxBooks() && overdueCount == 0);
        return vo;
    }
}
