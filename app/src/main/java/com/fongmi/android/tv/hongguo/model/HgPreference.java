package com.fongmi.android.tv.hongguo.model;

import java.util.ArrayList;
import java.util.List;

/**
 * 红果「看剧偏好」数据面（官方 UserPreferenceInfoResponse 逐字段定版，
 * 国内版包 classes11 rpc 模型）：
 *
 * <ul>
 *   <li>性别区＝data.common_data（PreferenceGenderData：title/text/sub_text/
 *       content_type/select_type＋content[] PreferenceContentData{id,content,
 *       status}）；content_type 为 "none" 时官方 PreferenceActivity 不走本面，
 *       解析同口径跳过。status 是 PreferenceStatus 枚举（not_set=0/like=1/
 *       dislike=2），selected 以 like 计；select_type 是 PreferenceSelectType
 *       （Single=1/Multi=2）。</li>
 *   <li>资料区＝data.preference_list[]（ProfilePreferenceData：preference_key/
 *       preference_name/style_type＋option_list[] ProfilePreferenceOption
 *       {id,content,is_selected,is_common_choose}）；style_type 是
 *       ProfilePreferenceStyleType（PreferenceSelect=0 单选/MultiSelect=1/
 *       MultiTagSelect=2 多选/Input=3）。</li>
 *   <li>题材区＝data.genre_preference.genres[]（GenreSelectItem：genre/
 *       text/is_selected），多选；官方客户端该区标题在未反编译的
 *       Compose/碎片代码里未抠死，故本模型 title 恒为 null、页面不自创标题。</li>
 * </ul>
 *
 * <p>选项带回包原态（fetchedSelected），保存时只提交与原态不同的选项
 * （增量口径，避免整表回写把别处设过的 dislike 等状态冲掉）。回包真容
 * 与保存收否待新版首登实拉验（与预约列表同级状态）。</p>
 */
public final class HgPreference {

    /** 性别区（common_data）。 */
    public static final int KIND_GENDER = 0;
    /** 资料区（preference_list 的一项）。 */
    public static final int KIND_PROFILE = 1;
    /** 题材区（genre_preference）。 */
    public static final int KIND_GENRE = 2;

    public static final class Option {
        /** 服务端 id（题材区为 genre 数字串）。 */
        public String id;
        /** 选项文案。 */
        public String text;
        /** 页面工作态（用户点选后的当前态）。 */
        public boolean selected;
        /** 回包原态（保存增量比对基准，不随点选变）。 */
        public boolean fetchedSelected;
    }

    public static final class Section {
        public int kind;
        /** 区分键：性别区＝content_type、资料区＝preference_key、题材区＝"genre"。 */
        public String key;
        /** 区标题（题材区恒 null，不自创）。 */
        public String title;
        /** 区副文（仅性别区 sub_text 可能有）。 */
        public String subText;
        /** true＝多选，false＝单选。 */
        public boolean multi;
        public final List<Option> options = new ArrayList<>();
    }

    public final List<Section> sections = new ArrayList<>();

    public boolean isEmpty() {
        return sections.isEmpty();
    }

    /** 与回包原态不同的选项（保存增量），按区/选项原序。 */
    public List<Option> changedOptions() {
        List<Option> out = new ArrayList<>();
        for (Section s : sections) {
            for (Option o : s.options) {
                if (o.selected != o.fetchedSelected) out.add(o);
            }
        }
        return out;
    }

    public boolean hasChanges() {
        for (Section s : sections) {
            for (Option o : s.options) {
                if (o.selected != o.fetchedSelected) return true;
            }
        }
        return false;
    }
}
