package com.fongmi.android.tv.music;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
* 瓜子（GZ MUSIC）歌词版本对歌分级与匹配度——照瓜子反编译源逐项移植，不许自编：
* 分级函数 sq4.t(SearchSong, List)（bq4＝Match(song, level)，level 为 double 等级值），
* 辅助函数 sq4.C（双字组 Dice 相似度）、sq4.D（标题清洗）、sq4.u（歌手拆分归一）；
* 等级→分数映射在候选取词流 gq4 里（copy 时按 level 重算 matchScore）；
* 显示口径 LyricsCandidate.getMatchPercentText＝(int)(matchScore×100)＋"%"（截断）。
* 本类只做「定级＋打分」：候选歌的选定仍走星辰自家 matchSong（定案行为不动），
* 选中的命中拿它的标题／歌手／时长到这里定瓜子级别，供版本弹窗排序与匹配度显示。
* 瓜子侧没有对应物的星辰版本（无候选歌元数据的产出步）在 LyricVersion 里记未定级，
* 不拿本类硬算、不冒充。
*/
public final class GzLyricMatch {

private GzLyricMatch() {
}

// 瓜子 sq4.t 的标题黑名单（Pattern 标志 66＝CASE_INSENSITIVE｜UNICODE_CASE，逐字照抄）：
// 带伴奏／karaoke／纯音乐／live／demo 等括号标注的候选直接淘汰
private static final Pattern[] TITLE_REJECT = {
Pattern.compile("（\\s*(伴奏|karaoke|instrumental|卡拉ok|纯音乐|背景音乐|铃声|live|现场|demo)\\s*）",
Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE),
Pattern.compile("\\(\\s*(伴奏|karaoke|instrumental|卡拉ok|纯音乐|背景音乐|铃声|live|现场|demo)\\s*\\)",
Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE),
Pattern.compile("（[^）]*?(dj[\\s\\-_]?|remix|mix|cover|翻唱|翻自)[^）]*?）",
Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE),
Pattern.compile("\\([^)]*?(dj[\\s\\-_]?|remix|mix|cover|翻唱|翻自)[^)]*?\\)",
Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE),
Pattern.compile("\\b(cover|翻唱|翻自)\\b",
Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE),
};

private static final Pattern NON_WORD = Pattern.compile("[^\\p{L}\\p{N}]");
private static final Pattern FULL_PAREN = Pattern.compile("（[^）]*）");
private static final Pattern HALF_PAREN = Pattern.compile("\\([^)]*\\)");
private static final Pattern BOOK_BRACKET = Pattern.compile("《[^》]*》");
private static final Pattern SQUARE_BRACKET = Pattern.compile("\\[[^]]*\\]");
private static final Pattern WHITESPACE = Pattern.compile("\\s+");
private static final Pattern FEAT = Pattern.compile("\\s*(?:feat\\.?|ft\\.?|featuring)\\s*",
Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
private static final Pattern VS = Pattern.compile("\\s*(?:vs\\.?|VS)\\s*");
private static final Pattern X_SEP = Pattern.compile("\\s+[xX]\\s+");
private static final Pattern ARTIST_SPLIT = Pattern.compile("[、,＆&·|／]+");
private static final Pattern SLASH_SPLIT = Pattern.compile("[/\\\\-]");

/**
* 瓜子分级（sq4.t 单候选口径）：返回等级值 3.0／2.0／1.5／1.4／1.0；0＝被瓜子门淘汰
* （候选无源内 id、时长差超 15 秒、标题命中黑名单、或相似度门不过）。
* 输入与瓜子 SearchSong 同义：标题、歌手、时长毫秒、候选源内 id。
*/
public static double level(String targetTitle, String targetArtist, long targetDurationMs,
String candTitle, String candArtist, long candDurationMs,
String candSourceId) {
if (isBlank(candSourceId)) return 0;
long targetSec = targetDurationMs / 1000;
long candSec = candDurationMs / 1000;
if (targetSec > 0 && candSec > 0 && Math.abs(targetSec - candSec) > 15) return 0;
String candTitleSafe = candTitle == null? "": candTitle;
for (Pattern p: TITLE_REJECT) {
if (p.matcher(candTitleSafe).find()) return 0;
}
String normTarget = norm(targetTitle);
String normCand = norm(candTitle);
String cleanTarget = clean(targetTitle);
String cleanCand = clean(candTitle);
List<String> artistsTarget = artists(targetArtist);
List<String> artistsCand = artists(candArtist);
boolean artistsEqual =!artistsTarget.isEmpty() &&!artistsCand.isEmpty()
&& artistsTarget.size() == artistsCand.size()
&& artistsTarget.equals(artistsCand);
boolean artistsSubset = false;
if (!artistsEqual &&!artistsTarget.isEmpty() &&!artistsCand.isEmpty()) {
if (artistsCand.size() < artistsTarget.size()) {
artistsSubset = artistsTarget.containsAll(artistsCand);
} else {
artistsSubset = artistsCand.containsAll(artistsTarget);
}
}
if (normCand.equals(normTarget) && artistsEqual) return 3.0;
if (cleanCand.equals(cleanTarget) && artistsEqual) return 2.0;
if (normCand.equals(normTarget) && artistsSubset) return 1.5;
if (cleanCand.equals(cleanTarget) && artistsSubset) return 1.4;
double titleDice = dice(cleanCand, cleanTarget);
double artistDice = dice(join(artistsCand), join(artistsTarget));
if (titleDice > 0.9 && artistDice > 0.85) return 1.0;
return 0;
}

/** 瓜子等级→匹配度分数（gq4 映射，逐档照抄）：≥3→1.0、≥2→0.85、≥1.5→0.75、≥1.4→0.65、其余（1.0 级）→0.5 */
public static float scoreForLevel(double level) {
if (level >= 3.0) return 1.0f;
if (level >= 2.0) return 0.85f;
if (level >= 1.5) return 0.75f;
if (level >= 1.4) return 0.65f;
return 0.5f;
}

/** 瓜子显示口径（getMatchPercentText）：(int)(分数×100)，截断不四舍五入 */
public static int percentForLevel(double level) {
return (int) (scoreForLevel(level) * 100.0f);
}

/** sq4.C：双字组（bigram）Dice 相似度——全等 1.0、任一为空 0.0、集合去重后 2×交集／两集之和 */
static double dice(String a, String b) {
if (a.equals(b)) return 1.0;
if (a.length() == 0 || b.length() == 0) return 0.0;
LinkedHashSet<String> setA = bigrams(a);
LinkedHashSet<String> setB = bigrams(b);
int inter = 0;
for (String s: setA) if (setB.contains(s)) inter++;
return (inter * 2.0) / (setA.size() + setB.size());
}

private static LinkedHashSet<String> bigrams(String s) {
LinkedHashSet<String> set = new LinkedHashSet<>();
for (int i = 0; i < s.length() - 1; i++) set.add(s.substring(i, i + 2));
return set;
}

/** 标题硬归一（sq4.t 内联）：小写后抹掉一切非文字非数字字符 */
static String norm(String s) {
if (s == null) return "";
return NON_WORD.matcher(s.toLowerCase(Locale.ROOT)).replaceAll("");
}

/** sq4.D 标题清洗：依次剥全角括号、半角括号、书名括号、方括号内容，再抹空白、小写、去首尾空白 */
static String clean(String s) {
if (s == null) return "";
String r = FULL_PAREN.matcher(s).replaceAll("");
r = HALF_PAREN.matcher(r).replaceAll("");
r = BOOK_BRACKET.matcher(r).replaceAll("");
r = SQUARE_BRACKET.matcher(r).replaceAll("");
r = WHITESPACE.matcher(r).replaceAll("");
return r.toLowerCase(Locale.ROOT).trim();
}

/**
* sq4.u 歌手拆分归一：feat./ft./featuring、vs、" x " 先归一成顿号，再按
* [、,＆&·|／] 切分；每段抹空白小写，空段与「群星／variousartists」丢弃；
* 段内再按 /、\、- 二次切分；保序去重。
*/
static List<String> artists(String s) {
LinkedHashSet<String> out = new LinkedHashSet<>();
if (s == null) return new ArrayList<>(out);
String n = FEAT.matcher(s).replaceAll("、");
n = VS.matcher(n).replaceAll("、");
n = X_SEP.matcher(n).replaceAll("、");
for (String part: split(ARTIST_SPLIT, n)) {
String flat = WHITESPACE.matcher(part).replaceAll("").toLowerCase(Locale.ROOT);
if (flat.length() == 0 || flat.equals("群星") || flat.equals("variousartists")) continue;
for (String sub: split(SLASH_SPLIT, part)) {
String item = WHITESPACE.matcher(sub).replaceAll("").toLowerCase(Locale.ROOT);
if (item.length() > 0) out.add(item);
}
}
return new ArrayList<>(out);
}

/** 瓜子侧的切分口径：按匹配段切开、尾段照收（与 Kotlin split(limit=0) 的段序列一致，空段保留给上层判空） */
private static List<String> split(Pattern p, String s) {
List<String> parts = new ArrayList<>();
Matcher m = p.matcher(s);
int i = 0;
boolean found = false;
while (m.find()) {
found = true;
parts.add(s.substring(i, m.start()));
i = m.end();
}
if (!found) {
parts.add(s);
return parts;
}
parts.add(s.substring(i));
return parts;
}

private static String join(List<String> items) {
StringBuilder sb = new StringBuilder();
for (int i = 0; i < items.size(); i++) {
if (i > 0) sb.append(' ');
sb.append(items.get(i));
}
return sb.toString();
}

private static boolean isBlank(String s) {
return s == null || s.trim().isEmpty();
}
}
