package com.library.service;

import com.github.pagehelper.PageHelper;
import com.github.pagehelper.PageInfo;
import com.library.common.PageResult;
import com.library.common.exception.BusinessException;
import com.library.entity.Book;
import com.library.mapper.BookMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 图书服务
 */
@Service
public class BookService {

    @Autowired
    private BookMapper bookMapper;

    @Autowired
    private CategoryService categoryService;

    /**
     * 图书列表（支持分类递归过滤）
     */
    public PageResult<Book> list(int page, int size, String keyword, Long categoryId) {
        List<Long> categoryIds = null;
        if (categoryId != null && categoryId > 0) {
            categoryIds = categoryService.collectDescendantIds(categoryId);
        }
        PageHelper.startPage(page, size);
        List<Book> list = bookMapper.selectList(keyword, categoryIds);
        PageInfo<Book> info = new PageInfo<>(list);
        return new PageResult<>(info.getList(), info.getTotal());
    }

    /**
     * 新增图书
     */
    public Book add(Book book) {
        if (bookMapper.selectByIsbn(book.getIsbn()) != null) {
            throw new BusinessException("ISBN 已存在");
        }
        if (book.getTotalCount() == null || book.getTotalCount() < 1) {
            book.setTotalCount(1);
        }
        book.setAvailableCount(book.getTotalCount());
        book.setStatus(1);
        if (book.getCoverUrl() == null) book.setCoverUrl("");
        if (book.getDescription() == null) book.setDescription("");
        if (book.getPublisher() == null) book.setPublisher("");
        if (book.getLocation() == null) book.setLocation("");
        bookMapper.insert(book);
        return book;
    }
}
