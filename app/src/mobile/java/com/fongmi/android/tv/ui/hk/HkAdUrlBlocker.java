package com.fongmi.android.tv.ui.hk;

import java.util.List;

/**
 * 照 8.83 com.example.hikerview.ui.browser.model.AdUrlBlocker 的 shouldBlock/checkBlockRule/includes 转写。
 * 规则语义（照原文）：blockForward@ 开头恒不拦；@domain= 后段要求当前域名包含该段才继续判定；
 * "!!" 开头是例外（白名单）规则——任一片段命中回 false（不拦），全不命中回 true；
 * 片段 "$dom" 比对请求域名包含当前域名、"$topdom" 比对请求域名包含当前域名的二级域名、余为通配 includes。
 * includes 为按 "*" 切段后的顺序子串匹配（原版 >2 段/2 段/1 段三支语义经核等价于同一顺序匹配）。
 */
public class HkAdUrlBlocker {

    public static boolean shouldBlock(List<String> rules, String lastDom, String url) {
        if (rules == null || rules.isEmpty()) return false;
        String dom = HkStringUtil.getDom(lastDom);
        if (url == null) return false;
        for (String rule : rules) {
            if (checkBlockRule(url, dom, rule)) return true;
        }
        return false;
    }

    public static boolean checkBlockRule(String url, String dom, String rule) {
        if (HkStringUtil.isEmpty(rule) || HkStringUtil.isEmpty(url)) return false;
        if (rule.startsWith("blockForward@")) return false;
        String[] domainParts = rule.split("@domain=");
        String r = rule;
        if (domainParts.length >= 2) {
            r = domainParts[0];
            if (dom == null || !dom.contains(domainParts[1])) return false;
        }
        if (r.startsWith("!!")) {
            String sub = r.substring(2);
            String[] segments = sub.split("!!");
            boolean matched = false;
            for (String seg : segments) {
                if ("$dom".equals(seg)) {
                    String reqDom = HkStringUtil.getDom(url);
                    if (reqDom != null && dom != null && reqDom.contains(dom)) {
                        matched = true;
                        break;
                    }
                    if (includes(url, dom)) {
                        matched = true;
                        break;
                    }
                } else if ("$topdom".equals(seg)) {
                    String second = HkStringUtil.getSecondDom(dom);
                    String reqDom = HkStringUtil.getDom(url);
                    if (reqDom != null && second != null && reqDom.contains(second)) {
                        matched = true;
                        break;
                    }
                } else {
                    if (includes(url, seg)) {
                        matched = true;
                        break;
                    }
                }
            }
            return !matched;
        }
        return includes(url, r);
    }

    private static boolean includes(String url, String pattern) {
        if (url == null || pattern == null) return false;
        String[] parts = pattern.split("\\*");
        String rest = url;
        for (String part : parts) {
            int idx = rest.indexOf(part);
            if (idx < 0) return false;
            rest = rest.substring(idx + part.length());
        }
        return true;
    }
}
