package com.example.videocall_marching_language.repository;

import com.example.videocall_marching_language.entity.TagCategory;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ITagCategoryRepository  extends JpaRepository<TagCategory, Long> {
}
