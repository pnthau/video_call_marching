package com.example.videocall_marching_language.repository;

import com.example.videocall_marching_language.dto.IScriptSummaryView;
import com.example.videocall_marching_language.dto.ScriptDTO;
import com.example.videocall_marching_language.entity.Script;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface IScriptRepository extends JpaRepository<Script, Long> {
    // Lấy script theo 1 chủ đề (theo ID)
    List<Script> findByTagId(Long tagId);

    // Lấy script theo nhiều tên chủ đề (vd: tags=n5,combini)
    List<IScriptSummaryView> findByTagNameIn(List<String> tagNames);
}
