package com.fongmi.android.tv.api.hk;

import java.util.HashMap;
import java.util.Map;

/**
 * 海阔规则解析出的一条列表/搜索结果。
 * 对应设计文档 §5 HkItem。
 */
public class HkItem {

    private String title;
    private String url;
    private String pic;
    private String desc;
    /** 海阔 col_type（movie_3/pic_1_full/input 等，deleteItemByCls/updateItem 用）。 */
    private String colType = "";
    /** 条目 extra 对象：pageTitle/newWindow/id/cls/lineVisible/textAlign 等（updateItem/deleteItem/findItemsByCls 用）。 */
    private Map<String, String> extra = new HashMap<>();
    /** 搜索多内容字段（官方 content）。 */
    private String content = "";
    /** 明细线路名（官方 line，详情页按 line 分组用；列表里一般为空）。 */
    private String line = "";
    /** 8.83 DetailPage.findRule：条目级 find_rule（覆盖规则级）。 */
    private String findRule = "";
    /** 8.83 DetailPage.group：条目分组。 */
    private String group = "";
    /** 8.83 DetailPage.pages：条目分页标记。 */
    private String pages = "";
    /** 8.83 DetailPage.preRule：条目级 preRule。 */
    private String preRule = "";
    /** 8.83 DetailPage.rule：条目携带的规则串。 */
    private String rule = "";

    public HkItem() {
    }

    public HkItem(String title, String url, String pic, String desc) {
        this.title = title;
        this.url = url;
        this.pic = pic;
        this.desc = desc;
    }

    public String getTitle() {
        return title == null ? "" : title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getUrl() {
        return url == null ? "" : url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public String getPic() {
        return pic == null ? "" : pic;
    }

    public void setPic(String pic) {
        this.pic = pic;
    }

    public String getDesc() {
        return desc == null ? "" : desc;
    }

    public void setDesc(String desc) {
        this.desc = desc;
    }

    public String getColType() {
        return colType == null ? "" : colType;
    }

    public void setColType(String colType) {
        this.colType = colType;
    }

    public Map<String, String> getExtra() {
        return extra;
    }

    public void setExtra(Map<String, String> extra) {
        this.extra = extra == null ? new HashMap<>() : extra;
    }

    /** extra 里取单个值（id/cls/pageTitle 等），缺省 ""。 */
    public String getExtra(String key) {
        if (extra == null || key == null) return "";
        String v = extra.get(key);
        return v == null ? "" : v;
    }

    public String getContent() {
        return content == null ? "" : content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public String getLine() {
        return line == null ? "" : line;
    }

    public void setLine(String line) {
        this.line = line;
    }

    /** 8.83 DetailPage.findRule 对应。 */
    public String getFindRule() {
        return findRule == null ? "" : findRule;
    }

    public void setFindRule(String findRule) {
        this.findRule = findRule;
    }

    /** 8.83 DetailPage.group 对应。 */
    public String getGroup() {
        return group == null ? "" : group;
    }

    public void setGroup(String group) {
        this.group = group;
    }

    /** 8.83 DetailPage.pages 对应。 */
    public String getPages() {
        return pages == null ? "" : pages;
    }

    public void setPages(String pages) {
        this.pages = pages;
    }

    /** 8.83 DetailPage.preRule 对应。 */
    public String getPreRule() {
        return preRule == null ? "" : preRule;
    }

    public void setPreRule(String preRule) {
        this.preRule = preRule;
    }

    /** 8.83 DetailPage.rule 对应。 */
    public String getRule() {
        return rule == null ? "" : rule;
    }

    public void setRule(String rule) {
        this.rule = rule;
    }

    /**
     * 8.83 DetailPage.getImageUrl() 同款语义：pic_url → pic → picUrl 取首个非空。
     * 本类 pic 字段已是多 key 合并结果（pic_url/img/pic/…），此处直接返回 getPic()，
     * 保留与 8.83 一致的方法名供调用方按原版写法使用。
     */
    public String getImageUrl() {
        return getPic();
    }

    @Override
    public String toString() {
        return "HkItem{title='" + title + "', url='" + url + "', pic='" + pic + "', desc='" + desc + "'}";
    }

    /**
     * 搜索结果点击进详情/播放时转回通用条目（SearchResult 保持 8.83 原版纯净，转换放这边）。
     */
    public static HkItem fromSearchResult(SearchResult sr) {
        HkItem it = new HkItem();
        if (sr == null) return it;
        it.setTitle(sr.getTitle());
        it.setUrl(sr.getUrl());
        it.setPic(sr.getImg());
        it.setDesc(sr.getDesc());
        it.setContent(sr.getContent());
        return it;
    }
}
