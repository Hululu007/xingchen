package com.fongmi.android.tv.api.music;

/**
 * 落雪密钥问题错误（B4）：脚本拒绝消息携带显式 HTTP 401／403 标记时才包成此类型。
 * 通用失败（超时、断网、脚本内部错误）一律不包，不把通用失败冒充密钥问题。
 * 星辰没有瓜子的密钥下发链（无密钥配置、无密钥错误通道）：此类型只描述「脚本明确报了 401／403」，
 * 弹窗只挂接此类型，没有此错误绝不弹。
 */
public class LxKeyIssue extends RuntimeException {

    private final String platform;
    private final int statusCode;

    public LxKeyIssue(String platform, String detail, int statusCode) {
        super(detail == null ? "" : detail);
        this.platform = platform == null ? "" : platform;
        this.statusCode = statusCode;
    }

    public String getPlatform() {
        return platform;
    }

    public int getStatusCode() {
        return statusCode;
    }

    /**
     * 显式 401／403 标记才算密钥问题：statusCode／status 字段后跟 401／403（如 {"statusCode":401}），
     * 或 401 Unauthorized／403 Forbidden 文案。其它一切消息（含裸 401 数字、无关报错）都不算。
     */
    public static int detectStatusCode(String message) {
        if (message == null) return -1;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(
                "(?i)(?:statusCode|status)\\D{0,8}(401|403)|\\b(401)\\s+Unauthorized|\\b(403)\\s+Forbidden")
                .matcher(message);
        if (m.find()) {
            for (int g = 1; g <= m.groupCount(); g++) {
                if (m.group(g) != null) {
                    try {
                        return Integer.parseInt(m.group(g));
                    } catch (Throwable ignored) {
                    }
                }
            }
        }
        return -1;
    }

    public static boolean isKeyIssueMessage(String message) {
        return detectStatusCode(message) > 0;
    }
}
