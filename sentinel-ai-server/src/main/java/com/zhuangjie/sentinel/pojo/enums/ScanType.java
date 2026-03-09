package com.zhuangjie.sentinel.pojo.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum ScanType {

    FULL("FULL", "全量扫描"),
    INCREMENTAL("INCREMENTAL", "增量扫描");

    private final String code;
    private final String description;
}
