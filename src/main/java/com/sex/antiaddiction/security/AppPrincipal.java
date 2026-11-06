package com.sex.antiaddiction.security;

/** 令牌解析出来的调用者。kind 是 device 或 parent，subjectId 分别是 device.id 与 parent_account.id。 */
public record AppPrincipal(String kind, long subjectId) {

    public static final String HEADER = "X-Quizgate-Token";

    public boolean isDevice() {
        return "device".equals(kind);
    }

    public boolean isParent() {
        return "parent".equals(kind);
    }
}
