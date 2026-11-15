package com.library.mapper;

import com.library.entity.AiAgentTrace;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;

@Mapper
public interface AiAgentTraceMapper {

    void insert(AiAgentTrace t);

    /**
     * B2：向已落库轨迹追加授权审计事件（asked/decided 补录）。
     * 流结束后用户才点授权卡时，轨迹行已保存——decided 通过本语句按 turnId 追加，
     * 行不存在时静默跳过（该轮没有落轨迹，无需审计）。
     *
     * @param turnId 轮次标识
     * @param entry  审计条目（如 "decided:applyBorrow:allowed-once"）
     */
    void appendApprovalAudit(@Param("turnId") String turnId, @Param("entry") String entry);

    /** 管理端列表：按时间倒序分页 */
    List<AiAgentTrace> selectPage(@Param("offset") int offset,
                                  @Param("limit") int limit,
                                  @Param("status") String status,
                                  @Param("keyword") String keyword);

    int countAll(@Param("status") String status,
                 @Param("keyword") String keyword);

    AiAgentTrace selectById(@Param("id") Long id);

    /** 汇总统计：总轮次/总工具调用/门禁触发数/降级数/token 合计 */
    Map<String, Object> selectSummary();
}
