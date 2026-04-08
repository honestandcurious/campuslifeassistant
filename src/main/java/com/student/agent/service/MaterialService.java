package com.student.agent.service;

import com.student.agent.rag.CourseMaterialRagService;
import org.springframework.stereotype.Service;

@Service
public class MaterialService {

    private final CourseMaterialRagService courseMaterialRagService;

    public MaterialService(CourseMaterialRagService courseMaterialRagService) {
        this.courseMaterialRagService = courseMaterialRagService;
    }

    public String queryMaterials(String question) {
        return courseMaterialRagService.search(question);
    }
}
