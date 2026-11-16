package com.library.mapper;

import com.library.entity.AiMessage;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.util.List;

@Mapper
public interface AiMessageMapper {
    List<AiMessage> selectByConversationId(@Param("conversationId") Long conversationId);

    // 窗口分页：按 id 游标向后翻页（beforeId 为空取最新一页，否则取更早一页）
    List<AiMessage> selectHistoryPage(@Param("conversationId") Long conversationId,
                                      @Param("beforeId") Long beforeId,
                                      @Param("pageSize") int pageSize);

    // 前端历史分页：过滤 hidden 机制消息
    List<AiMessage> selectVisibleHistoryPage(@Param("conversationId") Long conversationId,
                                             @Param("beforeId") Long beforeId,
                                             @Param("pageSize") int pageSize);

    int countVisibleByConversationId(@Param("conversationId") Long conversationId);

    int countByConversationId(@Param("conversationId") Long conversationId);

    void insert(AiMessage m);

    void updateToolSnapshots(@Param("id") Long id, @Param("toolSnapshots") String toolSnapshots);

    // —— A2 消息分支模型 ——

    AiMessage selectById(@Param("id") Long id);

    /** 同轮分支组的全部消息（含 hidden 回执，激活态不限，按 id 升序）；groupKey 为空返回空列表 */
    List<AiMessage> selectGroupMessages(@Param("conversationId") Long conversationId,
                                        @Param("groupKey") String groupKey);

    /** 为历史消息补建分支组：把激活链消息回填同一 group_key（懒初始化，幂等） */
    void assignGroupKey(@Param("conversationId") Long conversationId,
                        @Param("ids") List<Long> ids,
                        @Param("groupKey") String groupKey);

    /** 同轮分支组内全部兄弟 user 消息（激活态不限）；groupKey 为空返回空列表 */
    List<AiMessage> selectSiblings(@Param("conversationId") Long conversationId,
                                   @Param("groupKey") String groupKey);

    /** 把分支组当前激活的消息全部置为非激活（切分支第一步） */
    void deactivateGroup(@Param("conversationId") Long conversationId,
                         @Param("groupKey") String groupKey);

    /** 激活指定消息（切分支第二步：目标分支的 user+assistant 消息链置 1） */
    void activateMessages(@Param("conversationId") Long conversationId,
                          @Param("ids") List<Long> ids);

    void deleteByConversationId(@Param("conversationId") Long conversationId);
}
