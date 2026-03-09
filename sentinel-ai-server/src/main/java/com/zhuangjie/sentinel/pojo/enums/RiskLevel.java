package com.zhuangjie.sentinel.pojo.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum RiskLevel {

    P0("P0", "紧急 - 必定慢SQL，需立即修复"),
    P1("P1", "高危 - 大概率慢SQL，建议尽快优化"),
    P2("P2", "中危 - 存在性能风险，建议优化"),
    P3("P3", "低危 - 轻微风险，可择期优化"),
    P4("P4", "安全 - 无明显性能风险");

    private final String code;
    private final String description;
}
