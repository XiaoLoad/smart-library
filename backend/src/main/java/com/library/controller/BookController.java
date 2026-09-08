package com.library.controller;

import com.library.common.PageResult;
import com.library.common.Result;
import com.library.dto.BookAddRequest;
import com.library.dto.BookUpdateRequest;
import com.library.entity.Book;
import com.library.mapper.BookMapper;
import com.library.security.CurrentUser;
import com.library.service.BookService;
import com.library.service.CategoryService;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

/**
 * 图书控制器
 */
@RestController
public class BookController {

    @Autowired
    private BookService bookService;

    @Autowired
    private CategoryService categoryService;

    @Autowired
    private BookMapper bookMapper;

    @GetMapping("/book/list")
    public Result<PageResult<Book>> list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Long categoryId) {
        return Result.success(bookService.list(page, size, keyword, categoryId));
    }

    @GetMapping("/book/category/tree")
    public Result<List<com.library.entity.Category>> categoryTree() {
        return Result.success(categoryService.categoryTree());
    }

    @PostMapping("/book/add")
    public Result<Book> add(@Valid @RequestBody BookAddRequest req) {
        CurrentUser cu = CurrentUser.get();
        if (cu.getUserType() != 3) {
            return Result.error(403, "仅管理员可新增图书");
        }
        Book book = new Book();
        book.setTitle(req.getTitle());
        book.setAuthor(req.getAuthor());
        book.setIsbn(req.getIsbn());
        book.setPublisher(req.getPublisher());
        book.setCategoryId(req.getCategoryId());
        book.setLocation(req.getLocation());
        book.setPrice(req.getPrice());
        book.setCoverUrl(req.getCoverUrl());
        book.setDescription(req.getDescription());
        book.setTotalCount(req.getTotalCount());
        return Result.success("新增成功", bookService.add(book));
    }

    @PostMapping("/book/update")
    public Result<Book> update(@Valid @RequestBody BookUpdateRequest req) {
        CurrentUser cu = CurrentUser.get();
        if (cu.getUserType() != 3) {
            return Result.error(403, "仅管理员可编辑图书");
        }
        Book book = new Book();
        book.setId(req.getId());
        book.setTitle(req.getTitle());
        book.setAuthor(req.getAuthor());
        book.setIsbn(req.getIsbn());
        book.setPublisher(req.getPublisher());
        book.setCategoryId(req.getCategoryId());
        book.setLocation(req.getLocation());
        book.setPrice(req.getPrice());
        book.setCoverUrl(req.getCoverUrl());
        book.setDescription(req.getDescription());
        book.setTotalCount(req.getTotalCount());
        return Result.success("修改成功", bookService.update(book));
    }
}
