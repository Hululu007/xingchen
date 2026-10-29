package com.fongmi.android.tv.hongguo;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 评论表情码渲染：官方表情码表在包内 assets/emoji_config.json（53 码，
 * third_id 1..53，码名逐字照表）；官方图形资源不在包内散图里（运行时
 * 远端下发，抠不到原图），这里按码名配 Unicode 表情字形等值渲染——
 * 显示为真图形、非官方原图（账 comment-page-completion 标明）。
 * 正文里的 [码] 文本替换为对应字形；不认识的码原样保留。
 * 发表时与官方同机制：编辑层点表情往正文插 [码] 文本、服务端存码。
 */
public final class HgEmoji {

    /** 码名（与官方 emoji_config.json 的 name 逐字一致，顺序＝third_id）。 */
    public static final String[] NAMES = {
            "微笑", "偷笑", "笑", "什么", "害羞", "爱慕", "飞吻", "奸笑", "尬笑", "思考",
            "撇嘴", "做鬼脸", "酷", "翻白眼", "惊呆", "震惊", "送心", "委屈", "快哭了", "笑哭",
            "哭", "大笑", "舔屏", "怒", "捂脸", "吐", "恐惧", "抓狂", "敬礼", "石化",
            "OK", "赞", "爱心", "伤心", "KISS", "懂了", "探究", "重拳出击", "吃瓜", "盯",
            "你细品", "赶稿中", "注意", "饱了", "码住", "学会了", "顶帖", "求爆更", "求关注", "爽",
            "鼓掌", "没看够", "送花",
    };

    /** 与 NAMES 同序的 Unicode 字形。 */
    public static final String[] GLYPHS = {
            "🙂", "🤭", "😃", "😮", "😊", "😍", "😘", "😏", "😅", "🤔",
            "😒", "😜", "😎", "🙄", "😲", "😱", "🥰", "🥺", "😢", "😂",
            "😭", "😄", "🤤", "😡", "🤦", "🤮", "😨", "😫", "🫡", "🗿",
            "👌", "👍", "❤️", "💔", "💋", "😌", "🧐", "👊", "🍉", "👀",
            "🤏", "✍️", "⚠️", "😋", "🔖", "🤓", "🔝", "🔥", "🙏", "😆",
            "👏", "🤩", "💐",
    };

    private static final Map<String, String> BY_NAME = new HashMap<>();
    private static final Pattern CODE = Pattern.compile("\\[([^\\[\\]]{1,8})\\]");

    static {
        for (int i = 0; i < NAMES.length; i++) BY_NAME.put(NAMES[i], GLYPHS[i]);
    }

    private HgEmoji() {
    }

    /** 正文渲染：已知 [码] 换成图形，未知码与普通文本原样。 */
    public static String render(String text) {
        if (text == null || text.indexOf('[') < 0) return text == null ? "" : text;
        Matcher m = CODE.matcher(text);
        StringBuffer sb = new StringBuffer();
        boolean hit = false;
        while (m.find()) {
            String glyph = BY_NAME.get(m.group(1));
            if (glyph == null) continue;
            hit = true;
            m.appendReplacement(sb, Matcher.quoteReplacement(glyph));
        }
        if (!hit) return text;
        m.appendTail(sb);
        return sb.toString();
    }
}
