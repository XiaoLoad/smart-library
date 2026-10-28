package com.library.mapper;

import com.library.entity.AiConversation;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.util.List;

@Mapper
public interface AiConversationMapper {
    List<AiConversation> selectByUserId(@Param("userId") Long userId);

    AiConversation selectById(@Param("id") Long id);

    void insert(AiConversation c);

    void updateTitle(@Param("id") Long id, @Param("title") String title);

    void updatePermissionMode(@Param("id") Long id, @Param("permissionMode") String permissionMode);

    void deleteById(@Param("id") Long id);
}
