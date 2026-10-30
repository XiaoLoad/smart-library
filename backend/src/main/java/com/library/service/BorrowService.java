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
import org.springframework.dao.DuplicateKeyException;
import org.springframework.scheduling.annotation.Scheduled;
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
    @Autowired
    private FineMapper fineMapper;
    /** 馆内规则（逾期罚款费率等），管理员可在系统设置中调整 */
    @Autowired
    private LibraryConfigService libraryConfigService;

    /**
     * 同步借阅状态
     * 待取书超期 → 已取消 + 恢复库存
     * 借阅中超期 → 已逾期
     */
    // 定时同步借阅状态（替代原 list()/quota() 内的实时写库，避免读接口产生写副作用）
    @Scheduled(fixedDelay = 60000)
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
        return apply(CurrentUser.get().getUserId(), bookId);
    }

    /**
     * 借阅申请（显式操作人，供 AI 工具在无 ThreadLocal 的异步线程中调用）
     */
    @Transactional
    public Borrow apply(Long operatorUserId, Long bookId) {
        User user = userMapper.selectById(operatorUserId);
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
        // 逾期校验（书在手逾期；「逾期之后才申请归还、罚款未结算」的同样拦截）
        List<Borrow> overdue = borrowMapper.selectOverduePending(user.getId());
        if (!overdue.isEmpty()) {
            throw new BusinessException(400, overdueBlockMessage(overdue));
        }
        // 图书校验
        Book book = bookMapper.selectById(bookId);
        if (book == null) {
            throw new BusinessException(400, "图书不存在");
        }
        if (book.getAvailableCount() <= 0) {
            throw new BusinessException(400, "该图书暂无库存");
        }

        String today = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        String prefix = "BR" + today;
        LocalDateTime now = LocalDateTime.now();

        Borrow record = new Borrow();
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

        // 借阅码按天全局，并发下 selectMaxSeqOfDay+1 可能取到相同序号；库表已有 uk_borrow_no
        // 唯一约束，冲突时捕获 DuplicateKeyException 后重取序号重试（最多 3 次）。
        // 库存扣减放在插入成功之后，且用条件更新，确保不重复扣减、不超借。
        int maxAttempts = 3;
        for (int attempt = 0; attempt < maxAttempts; attempt++) {
            Integer maxSeq = borrowMapper.selectMaxSeqOfDay(prefix);
            int seq = (maxSeq == null ? 0 : maxSeq) + 1;
            String borrowNo = prefix + String.format("%04d", seq);
            record.setBorrowNo(borrowNo);
            record.setQrPayload(String.format(
                    "{\"borrowNo\":\"%s\",\"userId\":%d,\"bookId\":%d,\"bookTitle\":\"%s\"}",
                    borrowNo, user.getId(), book.getId(), book.getTitle()));
            try {
                borrowMapper.insert(record);
                int rows = bookMapper.decreaseAvailable(book.getId(), 1);
                if (rows == 0) {
                    throw new BusinessException(400, "该图书暂无库存");
                }
                return record;
            } catch (DuplicateKeyException e) {
                // 借阅码并发冲突，重取序号重试
            }
        }
        throw new BusinessException(500, "生成借阅码失败，请稍后重试");
    }

    /**
     * 取书确认（管理员，支持批量，供借阅码弹窗等场景）
     */
    public PickupResultVo pickup(List<String> borrowNos) {
        CurrentUser cu = CurrentUser.get();
        if (cu.getUserType() != 3) {
            throw new BusinessException(403, "仅管理员可取书确认");
        }
        return pickup(borrowNos, cu.getUserId());
    }

    /**
     * 取书确认（显式管理员 ID，供 AI 工具调用）
     */
    @Transactional
    public PickupResultVo pickup(List<String> borrowNos, Long adminId) {
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
                doPickup(record, adminId);
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
    private void doPickup(Borrow record, Long adminId) {
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
                adminId
        );
    }

    /**
     * 归还入口（按角色分流）
     * 用户：发起归还申请 1/3 → 5（待确认归还），库存不变，需管理员确认
     * 管理员：直接归还 1/3 → 2（柜台现场收书场景），库存 +1
     */
    public Borrow returnBook(Long id, String borrowNo) {
        CurrentUser cu = CurrentUser.get();
        return returnBook(cu.getUserId(), cu.getUserType(), id, borrowNo);
    }

    /**
     * 归还入口（显式操作人，供 AI 工具在无 ThreadLocal 的异步线程中调用）
     */
    @Transactional
    public Borrow returnBook(Long operatorUserId, Integer operatorUserType, Long id, String borrowNo) {
        Borrow record = findRecord(id, borrowNo);
        // 非管理员只能操作本人记录
        if (operatorUserType != 3 && !record.getUserId().equals(operatorUserId)) {
            throw new BusinessException(403, "只能归还本人借阅的图书");
        }
        if (record.getStatus() != 1 && record.getStatus() != 3) {
            throw new BusinessException("该记录状态不可归还");
        }
        LocalDateTime now = LocalDateTime.now();
        if (operatorUserType == 3) {
            // 管理员直接归还，罚款按当前时间结算
            return completeReturn(record, now, operatorUserId, "已归还（管理员确认）");
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
                // 罚款费率取馆内规则配置（系统设置 · 借阅规则，默认 0.50 元/天）
                fine.setAmount(libraryConfigService.getFineRatePerDay()
                        .multiply(java.math.BigDecimal.valueOf(days)));
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
    public PickupResultVo batchProcess(List<Long> ids, List<String> borrowNos) {
        CurrentUser cu = CurrentUser.get();
        if (cu.getUserType() != 3) {
            throw new BusinessException(403, "仅管理员可批量办理");
        }
        return batchProcess(ids, borrowNos, cu.getUserId());
    }

    /**
     * 批量办理（显式管理员 ID，供 AI 工具在无 ThreadLocal 的异步线程中调用）。
     * 调用方需自行保证操作者为管理员。
     */
    @Transactional
    public PickupResultVo batchProcess(List<Long> ids, List<String> borrowNos, Long adminId) {
        List<Long> idList = ids == null ? Collections.emptyList() : ids;
        List<String> noList = borrowNos == null ? Collections.emptyList() : borrowNos;
        List<Map<String, Object>> results = new ArrayList<>(idList.size() + noList.size());
        int success = 0;
        for (Long id : idList) {
            Map<String, Object> r = new HashMap<>();
            r.put("requestId", id);
            success += processOne(r, id, null, adminId);
            results.add(r);
        }
        for (String no : noList) {
            Map<String, Object> r = new HashMap<>();
            r.put("requestBorrowNo", no);
            success += processOne(r, null, no, adminId);
            results.add(r);
        }
        return new PickupResultVo(results, success, idList.size() + noList.size() - success);
    }

    /**
     * 按状态分流办理单条记录
     * @return 1 成功 / 0 失败（失败原因写入 result.message）
     */
    private int processOne(Map<String, Object> result, Long id, String borrowNo, Long adminId) {
        try {
            Borrow record = findRecord(id, borrowNo);
            result.put("id", record.getId());
            result.put("borrowNo", record.getBorrowNo());
            result.put("bookTitle", record.getBookTitle());
            result.put("userName", record.getUserName());
            String action;
            switch (record.getStatus()) {
                case 0 -> {
                    doPickup(record, adminId);
                    action = "pickup";
                }
                case 1, 3 -> {
                    completeReturn(record, LocalDateTime.now(), adminId, "已归还（柜台办理）");
                    action = "return";
                }
                case 5 -> {
                    LocalDateTime effective = record.getReturnRequestTime() != null
                            ? record.getReturnRequestTime() : LocalDateTime.now();
                    completeReturn(record, effective, adminId, "已归还");
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
        CurrentUser cu = CurrentUser.get();
        return renew(cu.getUserId(), cu.getUserType(), id, borrowNo);
    }

    /**
     * 续借（显式操作人，供 AI 工具在无 ThreadLocal 的异步线程中调用）。
     * 非管理员只能续借本人记录。
     */
    @Transactional
    public Borrow renew(Long operatorUserId, Integer operatorUserType, Long id, String borrowNo) {
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
        if (operatorUserType != 3 && !record.getUserId().equals(operatorUserId)) {
            throw new BusinessException(403, "只能续借本人借阅的图书");
        }
        if (record.getStatus() != 1) {
            throw new BusinessException("只有借阅中的记录可续借");
        }
        if (record.getRenewCount() >= record.getRenewMaxCount()) {
            throw new BusinessException("续借次数已用完");
        }
        // 有其他逾期不可续借（书在手逾期 / 逾期归还罚款未结算）
        List<Borrow> overdueForRenew = borrowMapper.selectOverduePending(record.getUserId());
        if (!overdueForRenew.isEmpty()) {
            throw new BusinessException(overdueBlockMessage(overdueForRenew) + "，暂不可续借");
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
        List<Borrow> overdue = borrowMapper.selectOverduePending(user.getId());
        int holding = (int) overdue.stream().filter(b -> b.getStatus() == 3).count();
        vo.setMaxBooks(limit.getMaxBooks());
        vo.setUsed(used);
        vo.setRemaining(Math.max(0, limit.getMaxBooks() - used));
        vo.setOverdueCount(overdue.size());
        vo.setOverdueHolding(holding);
        vo.setPendingReturnOverdue(overdue.size() - holding);
        vo.setCanBorrow(used < limit.getMaxBooks() && overdue.isEmpty());
        return vo;
    }

    /**
     * 借阅可借性预检：可借返回 null，不可借返回拒绝原因（不抛异常）。
     * 供 AI 工具在弹出授权卡之前预检——把业务拦截提前到用户确认之前，
     * 避免用户在授权卡上确认后才被 {@link #apply} 的校验打回。校验规则与 apply 保持一致。
     */
    public String checkBorrowable(Long userId) {
        User user = userMapper.selectById(userId);
        if (user == null || user.getStatus() != 1) {
            return "用户不存在或已禁用，无法办理借阅";
        }
        BorrowLimit limit = borrowLimitMapper.selectByType(user.getUserType());
        if (limit == null) {
            return "当前账号类型不可借阅";
        }
        int used = borrowMapper.countByUserAndStatuses(userId, List.of(0, 1, 5));
        if (used >= limit.getMaxBooks()) {
            return "借阅额度已用满（" + used + "/" + limit.getMaxBooks() + " 本），请先归还部分图书后再申请";
        }
        List<Borrow> overdue = borrowMapper.selectOverduePending(userId);
        if (!overdue.isEmpty()) {
            return overdueBlockMessage(overdue);
        }
        return null;
    }

    /**
     * 逾期未还拦截文案：区分「书在手逾期」与「逾期归还待确认（罚款未结算）」，带具体书名，
     * 避免笼统的「有逾期未还」让读者误以为是历史罚款未缴清。
     */
    private String overdueBlockMessage(List<Borrow> overdue) {
        String holding = titles(overdue.stream().filter(b -> b.getStatus() == 3).toList());
        String pending = titles(overdue.stream().filter(b -> b.getStatus() != 3).toList());
        StringBuilder msg = new StringBuilder();
        if (!holding.isEmpty()) {
            msg.append("您有逾期未还图书 ").append(holding).append("，请先归还后再借阅");
        }
        if (!pending.isEmpty()) {
            if (msg.length() > 0) {
                msg.append("；");
            }
            msg.append(pending).append("为逾期归还，待管理员确认结算罚款前暂不可借阅");
        }
        return msg.toString();
    }

    private String titles(List<Borrow> list) {
        return list.stream()
                .map(b -> "《" + b.getBookTitle() + "》")
                .reduce((a, b) -> a + "、" + b)
                .orElse("");
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
