package com.library.mapper;

import com.library.entity.AiMessage;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.util.List;

@Mapper
public interface AiMessageMapper {
    List<AiMessage> selectByConversationId(@Param("conversationId") Long conversationId);

    void insert(AiMessage m);

    void deleteByConversationId(@Param("conversationId") Long conversationId);
}
