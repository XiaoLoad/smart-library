package com.library.mapper;

import com.library.entity.AiAgentTrace;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;

@Mapper
public interface AiAgentTraceMapper {

    void insert(AiAgentTrace t);

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
