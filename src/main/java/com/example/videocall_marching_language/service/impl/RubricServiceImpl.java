package com.example.videocall_marching_language.service.impl;

import com.example.videocall_marching_language.entity.Rubric;
import com.example.videocall_marching_language.repository.IRubricRepository;
import com.example.videocall_marching_language.service.IRubricService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class RubricServiceImpl implements IRubricService {

    private final IRubricRepository rubricRepository;

    @Override
    public List<Rubric> findAll() {
        return rubricRepository.findAllByOrderByIdAsc();
    }

    @Override
    public List<Rubric> findAllActive() {
        return rubricRepository.findByIsActiveTrueOrderByIdAsc();
    }

    @Override
    public Optional<Rubric> findById(Long id) {
        return rubricRepository.findById(id);
    }

    @Override
    public Rubric save(Rubric rubric) {
        return rubricRepository.save(rubric);
    }

    @Override
    @Transactional
    public void toggleActive(Long id) {
        rubricRepository.findById(id).ifPresent(rubric -> {
            rubric.setActive(!rubric.isActive());
            rubricRepository.save(rubric);
        });
    }
}
