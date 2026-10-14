package com.fongmi.android.tv.ui.hk;

import android.app.Activity;
import android.content.Context;

import com.fongmi.android.tv.api.hk.HkParserKit;
import com.google.gson.Gson;

import java.util.function.Consumer;

/**
 * 照 8.83 com.example.hikerview.service.parser.IWebViewParser 转写。
 * generateExtra 把条目 extra(JSON) 映射为 HkX5Extra（只拷 ua/blockRules/disableX5/urlInterceptor/
 * referer/jsLoadingInject/redirect 七项 + js 尾巴，余字段保持默认，原版如此），
 * 并把 generateExtraJS 生成的轮询脚本（250ms 一次、上限 120 次、调 fy_bridge_app.finishParse(url, type, ticket)）
 * 拼在规则 js 之前作为最终注入脚本。
 * escapeJavaScriptString 照 org.adblockplus.libadblockplus.android.Utils 同名方法转写。
 */
public abstract class HkIWebViewParser {

    protected Consumer<String> consumer;

    public abstract void destroy();

    public abstract boolean finishParseInner(Context context, String url);

    public abstract boolean parse(Activity activity, String url, String extra, Consumer<String> consumer);

    protected HkX5Extra generateExtra(String extraJson, String jsExpr, String type, String ticket) {
        HkX5Extra extra = new HkX5Extra();
        String jsStr = "";
        if (HkStringUtil.isNotEmpty(extraJson)) {
            HkX5Extra parsed = new Gson().fromJson(extraJson, HkX5Extra.class);
            if (parsed != null) {
                if (HkStringUtil.isNotEmpty(parsed.getUa())) extra.setUa(parsed.getUa());
                if (HkParserKit.isNotEmpty(parsed.getBlockRules())) extra.setBlockRules(parsed.getBlockRules());
                if (parsed.isDisableX5()) extra.setDisableX5(true);
                if (HkStringUtil.isNotEmpty(parsed.getUrlInterceptor())) {
                    extra.setUrlInterceptor(parsed.getUrlInterceptor());
                }
                extra.setReferer(parsed.getReferer());
                extra.setJsLoadingInject(parsed.isJsLoadingInject());
                if (HkStringUtil.isNotEmpty(parsed.getJs())) jsStr = parsed.getJs();
                extra.setRedirect(parsed.isRedirect());
            }
        }
        extra.setJs(generateExtraJS(jsExpr, type, ticket) + jsStr);
        return extra;
    }

    private String generateExtraJS(String jsExpr, String type, String ticket) {
        StringBuilder sb = new StringBuilder();
        sb.append("      function getUrl() {\n        try {         return eval('");
        sb.append(escapeJavaScriptString(jsExpr));
        sb.append("');\n        } catch(e) {\n         return null;\n        }      }\nif(window._cc == null){\n            window._cc = 0;            function check() {\n                if (_cc > 120){                   fy_bridge_app.finishParse('', '");
        sb.append(type);
        sb.append("', '");
        sb.append(ticket);
        sb.append("');\n                   return;                }                _cc++;                setTimeout(() => {\n                    var url = getUrl();\n                    if (url != null) {\n                        fy_bridge_app.finishParse(url, '");
        sb.append(type);
        sb.append("', '");
        sb.append(ticket);
        sb.append("');\n                    } else {\n                        check();\n                    }\n                }, 250)\n            }\n            check();\n}\n");
        return sb.toString();
    }

    /** 照 adblockplus Utils.escapeJavaScriptString：\n→\\n、\r→\\r、" ' \ 前加反斜杠，末尾把 U+2028/U+2029 换成字面转义。 */
    public static String escapeJavaScriptString(String s) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\n') {
                sb.append("\\n");
            } else if (c == '\r') {
                sb.append("\\r");
            } else if (c == '"' || c == '\'' || c == '\\') {
                sb.append('\\').append(c);
            } else {
                sb.append(c);
            }
        }
        return sb.toString().replace("\u2028", "\\u2028").replace("\u2029", "\\u2029");
    }
}
