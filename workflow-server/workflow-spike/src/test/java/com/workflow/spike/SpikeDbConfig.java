package com.workflow.spike;

/**
 * Spike 测试数据库凭证解析：环境变量优先，其次系统属性，最后默认值。
 * 全部指向本地 workflow_spike 库；真实密码通过环境变量注入，不落 git。
 */
final class SpikeDbConfig {

    private static final String PREFIX = "WF_DB_";

    private SpikeDbConfig() {
    }

    static String url() {
        return firstNonNull(
            env("URL"),
            System.getProperty("spike.db.url"),
            "jdbc:mysql://127.0.0.1:3306/workflow_spike?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai&characterEncoding=utf8");
    }

    static String username() {
        return firstNonNull(
            env("USERNAME"),
            System.getProperty("spike.db.username"),
            "workflow");
    }

    static String password() {
        String password = firstNonNull(
            env("PASSWORD"),
            System.getProperty("spike.db.password"));
        if (password == null) {
            throw new IllegalStateException(
                "未提供 Spike 测试数据库密码：请设置环境变量 WF_DB_PASSWORD（值为你创建的 'workflow'@'localhost' 账号密码）");
        }
        return password;
    }

    private static String env(String key) {
        return System.getenv(PREFIX + key);
    }

    private static String firstNonNull(String... candidates) {
        for (String c : candidates) {
            if (c != null && !c.isBlank()) {
                return c;
            }
        }
        return null;
    }
}
