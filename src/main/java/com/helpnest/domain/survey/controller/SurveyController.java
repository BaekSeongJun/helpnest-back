// @owner BSJ
package com.helpnest.domain.survey.controller;

import com.helpnest.domain.survey.dto.SurveyResponse;
import com.helpnest.domain.survey.dto.SurveySubmitRequest;
import com.helpnest.domain.survey.service.SurveyService;
import com.helpnest.global.common.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 고객 설문 (FR-SRV-02, 03). 로그인 없이 메일의 토큰으로 접근한다 — SecurityConfig 가 /api/surveys/* 를 연다 */
@RestController
@RequestMapping("/api/surveys")
@RequiredArgsConstructor
public class SurveyController {

    private final SurveyService surveyService;

    @GetMapping("/{token}")
    public ApiResponse<SurveyResponse> get(@PathVariable String token) {
        return ApiResponse.ok(surveyService.get(token));
    }

    @PostMapping("/{token}")
    public ApiResponse<Void> submit(@PathVariable String token, @Valid @RequestBody SurveySubmitRequest req) {
        surveyService.submit(token, req.rating(), req.comment());
        return ApiResponse.ok();
    }
}
