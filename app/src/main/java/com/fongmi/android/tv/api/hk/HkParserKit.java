package com.fongmi.android.tv.api.hk;

import java.util.Collection;
import java.util.List;

/**
 * 8.83 解析器兼容工具包。
 * <p>
 * 直接移植自海阔 8.83（do not rewrite）：
 * <ul>
 * <li>{@code com.example.hikerview.utils.StringUtil} —— 仅移植解析器用到的 8 个方法</li>
 * <li>{@code org.apache.commons.lang3.StringUtils#replaceOnce} / {@code replaceOnceIgnoreCase}</li>
 * <li>{@code com.example.hikerview.ui.browser.util.CollectionUtil#isNotEmpty}</li>
 * <li>{@code com.example.hikerview.ui.browser.model.UrlDetector#clearTag}</li>
 * </ul>
 */
public final class HkParserKit {

    private HkParserKit() {
    }

    // ---- StringUtil.isEmpty / isNotEmpty (8.83 原样) ----

    public static boolean isEmpty(CharSequence s) {
        return s == null || s.length() == 0;
    }

    public static boolean isNotEmpty(CharSequence s) {
        return !isEmpty(s);
    }

    public static boolean isNotEmpty(Collection<?> c) {
        return c != null && !c.isEmpty();
    }

    // ---- StringUtil.arrayToString (8.83 原样) ----

    public static String arrayToString(String[] arr, int start, String sep) {
        return arrayToString(arr, start, arr == null ? 0 : arr.length, sep);
    }

    public static String arrayToString(String[] arr, int start, int end, String sep) {
        StringBuilder sb = new StringBuilder();
        if (arr == null || arr.length <= start) {
            return "";
        }
        if (arr.length <= 1) {
            return arr[0];
        }
        sb.append(arr[start]);
        for (int i = start + 1; i < arr.length && i < end; i++) {
            sb.append(sep).append(arr[i]);
        }
        return sb.toString();
    }

    // ---- StringUtil.listToString (8.83 原样) ----

    public static String listToString(List<String> list, String sep) {
        StringBuilder sb = new StringBuilder();
        if (list == null || list.size() <= 0) {
            return "";
        }
        if (list.size() <= 1) {
            return list.get(0);
        }
        sb.append(list.get(0));
        for (int i = 1; i < list.size(); i++) {
            sb.append(sep).append(list.get(i));
        }
        return sb.toString();
    }

    public static String listToString(List<String> list, int start, String sep) {
        StringBuilder sb = new StringBuilder();
        if (list == null || list.size() <= start) {
            return "";
        }
        if (list.size() <= 1) {
            return list.get(0);
        }
        sb.append(list.get(start));
        for (int i = start + 1; i < list.size(); i++) {
            sb.append(sep).append(list.get(i));
        }
        return sb.toString();
    }

    // ---- StringUtil.decodeConflictStr (8.83 原样) ----

    public static String decodeConflictStr(String str) {
        return isEmpty(str) ? str : str.replace("？？", "?").replace("＆＆", "&").replace("；；", ";").replace("，，", ",");
    }

    // ---- StringUtil.splitUrlByQuestionMark (8.83 原样) ----

    public static String[] splitUrlByQuestionMark(String str) {
        if (isEmpty(str)) {
            return new String[]{str};
        }
        String[] split = str.split("\\?");
        return split.length <= 1 ? split : new String[]{split[0], arrayToString(split, 1, "?")};
    }

    // ---- StringUtil.containsChinese (8.83 原样) ----

    public static boolean containsChinese(String str) {
        if (str == null) {
            return false;
        }
        for (char c : str.toCharArray()) {
            if (isChinese(c)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isChinese(char c) {
        Character.UnicodeBlock ub = Character.UnicodeBlock.of(c);
        return ub == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS
                || ub == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS
                || ub == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A
                || ub == Character.UnicodeBlock.GENERAL_PUNCTUATION
                || ub == Character.UnicodeBlock.CJK_SYMBOLS_AND_PUNCTUATION
                || ub == Character.UnicodeBlock.HALFWIDTH_AND_FULLWIDTH_FORMS;
    }

    // ---- StringUtil.getBaseUrl (8.83 原样) ----

    public static String getBaseUrl(String str) {
        if (isEmpty(str)) {
            return str;
        }
        String host = str.replace("http://", "").replace("https://", "").split("/")[0];
        if (str.startsWith("https")) {
            return "https://" + host;
        }
        return "http://" + host;
    }

    // ---- StringUtil.trimBlanks (8.83 原样，CommonParser.getUrlWithoutOr 用) ----

    public static String trimBlanks(String str) {
        if (str == null) {
            return "";
        }
        return str.trim().replaceAll("\\s+", " ");
    }

    // ---- commons-lang3 StringUtils.replaceOnce / replaceOnceIgnoreCase ----

    public static String replaceOnce(String text, String searchString, String replacement) {
        if (isEmpty(text) || isEmpty(searchString)) {
            return text;
        }
        int idx = text.indexOf(searchString);
        if (idx < 0) {
            return text;
        }
        return text.substring(0, idx) + replacement + text.substring(idx + searchString.length());
    }

    public static String replaceOnceIgnoreCase(String text, String searchString, String replacement) {
        if (isEmpty(text) || isEmpty(searchString)) {
            return text;
        }
        int idx = text.toLowerCase().indexOf(searchString.toLowerCase());
        if (idx < 0) {
            return text;
        }
        return text.substring(0, idx) + replacement + text.substring(idx + searchString.length());
    }

    // ---- UrlDetector.clearTag (8.83 原样) ----
    // 去掉 x5Play:// 前缀及 #xxx# 标记

    public static String clearTag(String str) {
        if (isEmpty(str)) {
            return str;
        }
        str = replaceOnce(str, "x5Play://", "");
        str = replaceOnce(str, "#ignoreVideo=true#", "");
        str = replaceOnce(str, "#isVideo=true#", "");
        str = replaceOnce(str, "#ignoreImg=true#", "");
        str = replaceOnce(str, "#immersiveTheme#", "");
        str = replaceOnce(str, "#noRecordHistory#", "");
        str = replaceOnce(str, "#noHistory#", "");
        str = replaceOnce(str, "#noLoading#", "");
        return str;
    }
}
