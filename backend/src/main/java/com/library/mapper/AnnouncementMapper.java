package com.library.mapper;

import com.library.entity.Announcement;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.util.List;

@Mapper
public interface AnnouncementMapper {
    List<Announcement> selectList();
    Announcement selectById(@Param("id") Long id);
    void insert(Announcement a);
    void deleteById(@Param("id") Long id);
    void updateTop(@Param("id") Long id, @Param("top") Integer top);
}
