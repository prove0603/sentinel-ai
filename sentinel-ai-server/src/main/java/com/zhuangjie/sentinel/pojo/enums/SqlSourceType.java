package com.zhuangjie.sentinel.pojo.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum SqlSourceType {

    MAPPER_XML("MAPPER_XML", "MyBatis Mapper XML"),
    QUERY_WRAPPER("QUERY_WRAPPER", "MyBatis-Plus QueryWrapper"),
    LAMBDA_WRAPPER("LAMBDA_WRAPPER", "MyBatis-Plus LambdaQueryWrapper"),
    ANNOTATION("ANNOTATION", "MyBatis @Select/@Insert/@Update/@Delete annotation");

    private final String code;
    private final String description;

    public static SqlSourceType fromCode(String code) {
        if (code == null) return MAPPER_XML;
        for (SqlSourceType type : values()) {
            if (type.code.equals(code)) return type;
        }
        return MAPPER_XML;
    }
}
