package com.fongmi.android.tv.ui.activity;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * 外部文件导入的定型判定（纯逻辑、零 Android 依赖，harness 可单机验）：
 * 先认文件名扩展名，认不出再嗅探内容头，只分 py / js 两大类——与插件管理
 * （PluginActivity）的两个页签一一对应。细分形态不在这里分：drpy/wv 的
 * 通道分发在 JsLoader 内部按内容判，音乐插件形态由 MusicSourceManager
 * .detectFormat 判（打开落点用），都不改落盘目录。
 * 扩展名是 .py/.js/.wv 时扩展名说了算，不许内容嗅探翻案（一个 .js 里
 * 出现 "class Spider" 字样不等于它是 py）。
 */
public final class PluginFileJudge {

    public enum Kind {PY, JS, UNKNOWN}

    public static final class Verdict {
        public final Kind kind;
        /** 落盘文件名（已清洗、扩展名已归一小写）；原名缺失/不可用时为 null，调用方按内容定名生成 */
        public final String targetName;

        Verdict(Kind kind, String targetName) {
            this.kind = kind;
            this.targetName = targetName;
        }
    }

    private PluginFileJudge() {
    }

    public static Verdict judge(String rawName, byte[] content) {
        String name = sanitize(rawName);
        String ext = extensionOf(name);
        if ("py".equals(ext)) return new Verdict(Kind.PY, normalizeExtCase(name));
        if ("js".equals(ext) || "wv".equals(ext)) return new Verdict(Kind.JS, normalizeExtCase(name));
        Kind sniffed = sniff(content);
        if (sniffed == Kind.UNKNOWN) return new Verdict(Kind.UNKNOWN, null);
        String suffix = sniffed == Kind.PY ? ".py" : ".js";
        if (name == null) return new Verdict(sniffed, null);
        // 有名但扩展名对不上（.txt 之类）：留基名换后缀，让插件页签能列出来
        String base = ext == null ? name : name.substring(0, name.length() - ext.length() - 1);
        if (base.isEmpty()) return new Verdict(sniffed, null);
        return new Verdict(sniffed, base + suffix);
    }

    /** 只留文件名本体：剥掉任何路径段与首尾空白；剥完为空（含纯 ".py" 这种没基名的）返回 null */
    static String sanitize(String rawName) {
        if (rawName == null) return null;
        String s = rawName.trim();
        int cut = Math.max(s.lastIndexOf('/'), s.lastIndexOf('\\'));
        if (cut >= 0) s = s.substring(cut + 1).trim();
        if (s.isEmpty() || ".".equals(s)) return null;
        if (s.lastIndexOf('.') == 0) return null; // 只有后缀没基名（".py"/".js" 这种）
        return s;
    }

    /**
     * 落盘名扩展名归一小写（基名原样）：「鲍鱼盒子.PY」与「鲍鱼盒子.py」是同一个
     * 脚本，不归一会在大小写敏感的文件系统上落成两个文件、列表双份——他
     * 2026-10-31 亲令同名直接覆盖，落盘名先归一才谈得上同名。
     */
    static String normalizeExtCase(String name) {
        String ext = extensionOf(name);
        if (ext == null) return name;
        return name.substring(0, name.length() - ext.length()) + ext;
    }

    /** 小写扩展名（不含点）；无扩展名返回 null */
    static String extensionOf(String name) {
        if (name == null) return null;
        int dot = name.lastIndexOf('.');
        if (dot <= 0 || dot == name.length() - 1) return null;
        return name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    /** 内容嗅探：只看头部 64KB；py 标志更专一先判，js 标志后判，都不中 UNKNOWN */
    static Kind sniff(byte[] content) {
        if (content == null || content.length == 0) return Kind.UNKNOWN;
        int len = Math.min(content.length, 64 * 1024);
        String head = new String(content, 0, len, StandardCharsets.UTF_8);
        String trimmed = head.trim();
        if (trimmed.startsWith("#!")) {
            if (trimmed.substring(0, Math.min(trimmed.length(), 128)).contains("python")) return Kind.PY;
            if (trimmed.substring(0, Math.min(trimmed.length(), 128)).contains("node")) return Kind.JS;
        }
        if (head.contains("class Spider")
                || head.contains("def homeContent(")
                || head.contains("def homeVideoContent(")
                || head.contains("from spider import")
                || head.contains("import spider")) {
            return Kind.PY;
        }
        if (head.contains("__JS_SPIDER__")
                || head.contains("module.exports")
                || head.contains("var rule")
                || head.contains("let rule")
                || head.contains("const rule")
                || head.contains("globalThis.")
                || head.contains("function homeContent")) {
            return Kind.JS;
        }
        return Kind.UNKNOWN;
    }
}
