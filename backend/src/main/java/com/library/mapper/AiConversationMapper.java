package com.library.mapper;

import com.library.entity.AiConversation;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.util.List;

@Mapper
public interface AiConversationMapper {
    List<AiConversation> selectByUserId(@Param("userId") Long userId);
}
