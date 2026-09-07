package com.library.mapper;

import com.library.entity.User;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 用户 Mapper
 */
@Mapper
public interface UserMapper {

    User selectById(@Param("id") Long id);

    User selectByUsername(@Param("username") String username);

    List<User> selectList(@Param("keyword") String keyword);

    List<User> selectAll();

    void updatePassword(@Param("id") Long id, @Param("password") String password);

    void insert(User user);

    void update(User user);
}
