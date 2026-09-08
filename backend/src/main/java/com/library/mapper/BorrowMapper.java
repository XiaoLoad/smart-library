package com.library.mapper;

import com.library.entity.Borrow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface BorrowMapper {

    List<Borrow> selectList(@Param("userId") Long userId,
                            @Param("isAdmin") boolean isAdmin,
                            @Param("status") Integer status,
                            @Param("search") String search);

    Borrow selectById(@Param("id") Long id);

    Borrow selectByBorrowNo(@Param("borrowNo") String borrowNo);

    int countByUserAndStatuses(@Param("userId") Long userId, @Param("statuses") List<Integer> statuses);

    Integer selectMaxSeqOfDay(@Param("prefix") String prefix);

    void insert(Borrow borrow);

    void updateStatusAndTimes(Borrow borrow);

    void updateStatus(@Param("id") Long id, @Param("status") Integer status, @Param("remark") String remark);

    void updateReturn(@Param("id") Long id, @Param("returnTime") LocalDateTime returnTime,
                      @Param("status") Integer status, @Param("remark") String remark,
                      @Param("confirmAdminId") Long confirmAdminId);

    void updateReturnRequest(@Param("id") Long id, @Param("returnRequestTime") LocalDateTime returnRequestTime,
                             @Param("remark") String remark);

    void updateReturnReject(@Param("id") Long id, @Param("status") Integer status, @Param("remark") String remark);

    int countOverduePending(@Param("userId") Long userId);

    void updateRenew(@Param("id") Long id, @Param("dueTime") LocalDateTime dueTime,
                     @Param("renewCount") Integer renewCount);

    void updatePickup(@Param("id") Long id, @Param("status") Integer status,
                      @Param("borrowTime") LocalDateTime borrowTime,
                      @Param("dueTime") LocalDateTime dueTime, @Param("remark") String remark,
                      @Param("pickupAdminId") Long pickupAdminId);
}
