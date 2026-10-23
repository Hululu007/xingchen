package com.fongmi.android.tv.api.hk;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Environment;
import android.util.Base64;

import com.fongmi.android.tv.App;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import okhttp3.FormBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * 海阔规则的存取管（设计文档 §4.1）。
 * 存储：filesDir/plugins/hk/&lt;规则名&gt;.json，与 plugins/py、plugins/js 并列。
 * 规则 JS 数据：filesDir/plugins/hk/data/&lt;规则名&gt;/（setItem/getItem 持久化，M2 用）。
 */
public class HkRuleManager {

    private static final String DIR = "plugins/hk";
    private static final String DATA_DIR = "plugins/hk/data";
    private static final String PREFS = "hk_rule";
    private static final String KEY_ENABLED_PREFIX = "enabled_";

    // 云口令（云剪贴板）：格式 云{version}oooole/{path}，version 为 1~10。
    // 逆向自海阔视界官方 App（com.example.hikerviewg v8.83）各 Importer 的真实通道（2026-10-10 实测）：
    //   云1oooole/{id}        → pastebin.com（需官方内嵌 API Key，本 App 不用）
    //   云1oooole/raw/{pasteId} → pastebin.com（GET https://pastebin.com/raw/{pasteId}）
    //   云2oooole/apidb/{key} → textdb.online（可用）
    //   云2oooole/p/{note_id} → netcut.cn（官方已下线 API，失效）
    //   云5oooole/{id}        → cmd.im（可用：GET https://cmd.im/{id} 取 .test_box 文本）
    //   云6oooole/xxxxxx/{path}(@{pwd}) → pasteme.tyrantg.com（可用：GET /api/getContent/{path}(@{pwd})）
    //   云7oooole/{note}      → note.ms（GET https://note.ms/{note} 取 textarea 文本，8.83 同款）
    //   云9oooole/{id}        → txtpad.cn（GET https://txtpad.cn/{id}）
    //   云10oooole/share/a/b/{key} → hastebin（GET hassdtebin.com/raw/{key}，回退 hastebin.com/raw/{key}）
    //   云3、云4、云8 在 8.83 中无实现，不支持
    private static final Pattern CLOUD_CODE_PATTERN =
            Pattern.compile("^云([1-9]|10)oooole(/.*)$");
    /**
     * 云口令提取（8.83 只取第一行；我们兼容"小程序：xxx"后缀/多行/前后缀文本）。
     * 用 find() 而非 matches()，从任意文本中提取口令子串。
     */
    private static final Pattern CLOUD_CODE_FIND =
            Pattern.compile("云([1-9]|10)oooole/\\S+");
    private static final String TEXTDB_API_UPDATE = "https://api.textdb.online/update/";
    private static final String TEXTDB_GET = "https://textdb.online/";
    private static final String PASTEME_API = "https://pasteme.tyrantg.com/api/getContent/";
    private static final String PASTEME_REFERER = "https://pasteme.tyrantg.com/";
    private static final String CMDIM_BASE = "https://cmd.im";

    private static volatile HkRuleManager instance;

    public static HkRuleManager get() {
        if (instance == null) {
            synchronized (HkRuleManager.class) {
                if (instance == null) {
                    instance = new HkRuleManager();
                    // 海阔引擎起家：落盘视频清一遍（潇洒哥 2026-10-18 定：6 小时/8 个文件/400MB 删最旧）。
                    // get() 是引擎进程级唯一入口、每个进程只建一次；清理走后台线程，不阻塞调用方。
                    // 不选 HkJsRuntime.init()：实例按规则创建，init 跑在规则单线程 executor 上，
                    // 放那会重复扫还占规则的执行线程。
                    try {
                        HkMediaCacheCleaner.scheduleClean(new File(HkJsRuntime.getFilePath("hiker://files/")));
                    } catch (Throwable ignored) {
                    }
                }
            }
        }
        return instance;
    }

    private Context appContext() {
        return App.get();
    }

    /** 规则目录，不存在则创建。 */
    public File getDir() {
        Context ctx = appContext();
        File dir = ctx == null ? new File(DIR) : new File(ctx.getFilesDir(), DIR);
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    /**
     * 读规则原始 JSON 文本（用于规则编辑）。不存在返回 null。
     */
    public String loadRuleJson(String title) {
        if (title == null || title.isEmpty()) return null;
        try {
            File f = new File(getDir(), safeFileName(title) + ".json");
            if (!f.exists()) return null;
            byte[] bytes = java.nio.file.Files.readAllBytes(f.toPath());
            return new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
        } catch (Throwable e) {
            return null;
        }
    }

    /**
     * 按标题取已安装的规则（用于版本更新比对）。不存在返回 null。
     */
    public HkRule getInstalledRule(String title) {
        if (title == null || title.isEmpty()) return null;
        try {
            File f = new File(getDir(), safeFileName(title) + ".json");
            if (!f.exists()) return null;
            byte[] bytes = java.nio.file.Files.readAllBytes(f.toPath());
            String json = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
            return parseJson(json);
        } catch (Throwable e) {
            return null;
        }
    }

    /** 某规则的持久化数据目录（setItem/getItem 用，M2）。 */
    public File getDataDir(String title) {
        File dir = new File(getDir(), "data/" + safeFileName(title));
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    private static final String CONFIG_DIR = "plugins/hk/config";

    /** 规则配置目录（海阔"长按规则→设置"的 key-value，如 config.host），不存在则创建。 */
    public File getConfigDir() {
        Context ctx = appContext();
        File dir = ctx == null ? new File(CONFIG_DIR) : new File(ctx.getFilesDir(), CONFIG_DIR);
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    /** 某规则的配置文件（JSON 对象），不存在返回 null。 */
    public File getRuleConfigFile(String title) {
        File f = new File(getConfigDir(), safeFileName(title) + ".json");
        return f.exists() ? f : null;
    }

    /** 读某规则的配置 JSON 文本，不存在/读失败返回 "{}"。 */
    public String loadRuleConfig(String title) {
        try {
            File f = getRuleConfigFile(title);
            if (f == null) return "{}";
            byte[] bytes = java.nio.file.Files.readAllBytes(f.toPath());
            String s = new String(bytes, java.nio.charset.Charset.forName("UTF-8")).trim();
            return s.isEmpty() ? "{}" : s;
        } catch (Throwable ignored) {
            return "{}";
        }
    }

    /** 写某规则的配置 JSON 文本（供后续"规则设置"页用）。 */
    public void saveRuleConfig(String title, String json) {
        try {
            File f = new File(getConfigDir(), safeFileName(title) + ".json");
            java.nio.file.Files.write(f.toPath(),
                    (json == null || json.trim().isEmpty() ? "{}" : json.trim()).getBytes("UTF-8"));
        } catch (Throwable ignored) {
        }
    }

    /**
     * 解析后尚未落盘的规则（含原始 JSON 文本，落盘时原样写入，不丢失未知字段）。
     * 一键导入先全部解析 → 弹窗让用户勾选 → 只把选中的 saveRule 落盘。
     */
    public static class ParsedRule {
        public final HkRule rule;
        public final String json;

        public ParsedRule(HkRule rule, String json) {
            this.rule = rule;
            this.json = json;
        }
    }

    /** .hkzip 包的解析结果：规则（未落盘）+ 附带资源（require.json / libs / data.zip 内条目）。 */
    public static class HkZipData {
        public final List<ParsedRule> rules = new ArrayList<>();
        public byte[] requireJson;
        public final Map<String, byte[]> libs = new LinkedHashMap<>();
        /** data.zip 内条目：key 为包内相对路径（如 plugins/globalmap.js），原样带出 */
        public final Map<String, byte[]> dataFiles = new LinkedHashMap<>();
    }

    /**
     * 某规则在外部 Documents 下的数据目录（hiker://files/data/&lt;规则名&gt;/ 的落盘根，
     * 与 HkJsRuntime.getFilePath("hiker://files/...") 的映射同根；8.83 往返语义）。
     */
    public File getExternalDataDir(String title) {
        Context ctx = appContext();
        File docs = ctx == null ? null : ctx.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS);
        File dir = new File(docs == null ? new File("Documents") : docs, "data/" + safeFileName(title));
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    /**
     * 从 JSON 文本解析规则：解析 → 校验，不落盘。
     *
     * @return 解析通过的规则
     * @throws Exception 解析失败或校验不通过时抛出，message 可直接提示用户
     */
    public HkRule parseJson(String json) throws Exception {
        if (json == null || json.trim().isEmpty()) throw new IllegalArgumentException("规则内容为空");
        String t = json.trim();
        // 形态预检：非对象开头的直接给人话错误，不把 Gson 原文（Expected BEGIN_OBJECT but was STRING…）抛给用户
        if (!t.startsWith("{")) {
            if (t.startsWith("[")) throw new IllegalArgumentException("内容是规则数组，请用批量导入");
            String head = t.length() > 30 ? t.substring(0, 30) + "…" : t;
            throw new IllegalArgumentException("内容不是规则 JSON（开头为：" + head + "），可能是口令已失效或云端内容已更换");
        }
        HkRule rule;
        try {
            rule = new Gson().fromJson(t, HkRule.class);
        } catch (Exception e) {
            throw new IllegalArgumentException("规则 JSON 解析失败：" + e.getMessage());
        }
        if (rule == null) throw new IllegalArgumentException("规则 JSON 解析失败");
        rule.validate();
        return rule;
    }

    /**
     * 规则落盘戳：每次 saveRule/delete 成功后 +1。
     * 供持有规则列表快照的页面（HkPageActivity）判断「我这份列表是不是已经过期」，
     * 变了才整表重读；没变不重读，避免回页重建列表丢滚动位置。
     */
    private static final java.util.concurrent.atomic.AtomicLong SAVE_STAMP =
            new java.util.concurrent.atomic.AtomicLong();

    public static long saveStamp() {
        return SAVE_STAMP.get();
    }

    /** 把解析好的规则落盘（保留原始 JSON 文本，不丢失未知字段）。 */
    public void saveRule(HkRule rule, String rawJson) throws Exception {
        if (rule == null) throw new IllegalArgumentException("规则为空");
        if (rawJson == null || rawJson.isEmpty()) rawJson = new Gson().toJson(rule);
        File target = new File(getDir(), safeFileName(rule.getTitle()) + ".json");
        // 同标题只许一份：先清掉其它文件名下标题相同的旧副本（历史遗留/改名残留），
        // 否则 getRules 去重按文件顺序取舍，导入新版后仍可能读到旧的那份。
        deleteSameTitleFiles(rule.getTitle(), target.getName());
        Files.write(target.toPath(), rawJson.getBytes(StandardCharsets.UTF_8));
        SAVE_STAMP.incrementAndGet();
        rule.setEnabled(isEnabled(rule.getTitle()));
    }

    /** 删除规则目录里「JSON 标题与 title 相同、但文件名不是 keepName」的文件（逐个解析比对，坏文件跳过）。 */
    private void deleteSameTitleFiles(String title, String keepName) {
        if (title == null || title.isEmpty()) return;
        try {
            File[] files = getDir().listFiles((d, name) -> name.endsWith(".json"));
            if (files == null) return;
            Gson gson = new Gson();
            for (File f : files) {
                if (f.getName().equals(keepName)) continue;
                try {
                    byte[] bytes = Files.readAllBytes(f.toPath());
                    HkRule other = gson.fromJson(new String(bytes, StandardCharsets.UTF_8), HkRule.class);
                    if (other != null && title.equals(other.getTitle())) f.delete();
                } catch (Exception ignored) {
                }
            }
        } catch (Throwable ignored) {
        }
    }

    /**
     * 从 JSON 文本导入规则：解析 → 校验 → 落盘（保留原始 JSON 文本，不丢失未知字段）。
     *
     * @return 导入成功的规则
     * @throws Exception 解析失败或校验不通过时抛出，message 可直接提示用户
     */
    public HkRule importJson(String json) throws Exception {
        HkRule rule = parseJson(json);
        saveRule(rule, json);
        return rule;
    }

    /** 从文件导入规则。 */
    public HkRule importFile(File src) throws Exception {
        if (src == null || !src.exists()) throw new IllegalArgumentException("规则文件不存在");
        byte[] bytes = Files.readAllBytes(src.toPath());
        return importJson(new String(bytes, StandardCharsets.UTF_8));
    }

    /**
     * 从 zip 包解析规则（不落盘）：解压后找出所有 .json（根目录或子目录均可），逐个解析。
     * 一个 zip 可包含多个规则；zip 内条目也可能是规则数组；单个损坏的条目只跳过、不影响其它。
     *
     * @return 解析成功的规则列表
     * @throws Exception zip 损坏 / 其中没有合法规则时抛出，message 可直接提示用户
     */
    public List<ParsedRule> parseZip(File zipFile) throws Exception {
        if (zipFile == null || !zipFile.exists()) throw new IllegalArgumentException("zip 文件不存在");
        List<ParsedRule> parsed = new ArrayList<>();
        List<String> failed = new ArrayList<>();
        try (ZipFile zip = new ZipFile(zipFile)) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry.isDirectory()) continue;
                String name = entry.getName();
                // 跳过 macOS 垃圾与隐藏文件
                String base = name.substring(name.lastIndexOf('/') + 1);
                if (base.startsWith(".")) continue;
                if (name.startsWith("__MACOSX/")) continue;
                // 只认 .json
                if (!base.toLowerCase(Locale.ROOT).endsWith(".json")) continue;
                byte[] bytes = readStream(zip.getInputStream(entry));
                try {
                    // 条目可能是单个规则，也可能是规则数组
                    parsed.addAll(parseJsonList(new String(bytes, StandardCharsets.UTF_8)));
                } catch (Exception e) {
                    failed.add(base + "（" + e.getMessage() + "）");
                }
            }
        } catch (ZipException e) {
            throw new IllegalArgumentException("不是有效的 zip 包或文件已损坏");
        }
        if (parsed.isEmpty()) {
            if (!failed.isEmpty()) throw new IllegalArgumentException("zip 中没有合法规则：" + failed.get(0));
            throw new IllegalArgumentException("zip 中没有找到 rule.json");
        }
        return parsed;
    }

    /**
     * 从 zip 包导入规则：解压后找出所有 rule.json（根目录或子目录均可），逐个导入。
     * 一个 zip 可包含多个规则；zip 内条目也可能是规则数组；单个损坏的条目只跳过、不影响其它。
     *
     * @return 导入成功的规则列表
     * @throws Exception zip 损坏 / 其中没有合法规则时抛出，message 可直接提示用户
     */
    public List<HkRule> importZip(File zipFile) throws Exception {
        List<ParsedRule> parsed = parseZip(zipFile);
        List<HkRule> imported = new ArrayList<>();
        for (ParsedRule p : parsed) {
            saveRule(p.rule, p.json);
            imported.add(p.rule);
        }
        return imported;
    }

    /**
     * 从 .hkzip 包解析：内容为 rule.json + require.json + libs.zip。
     * rule.json 只解析不落盘；require.json 与 libs.zip 缓存在返回对象中，
     * 用户勾选后只把选中规则的附带资源落盘到 data/&lt;规则名&gt;/。
     *
     * @return 解析结果
     * @throws Exception 包损坏 / 其中没有合法规则时抛出
     */
    public HkZipData parseHkZip(File hkzipFile) throws Exception {
        if (hkzipFile == null || !hkzipFile.exists()) throw new IllegalArgumentException("hkzip 文件不存在");
        HkZipData data = new HkZipData();
        List<String> failed = new ArrayList<>();
        try (ZipFile zip = new ZipFile(hkzipFile)) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry.isDirectory()) continue;
                String name = entry.getName();
                String base = name.substring(name.lastIndexOf('/') + 1);
                if (base.startsWith(".") || name.startsWith("__MACOSX/")) continue;
                String lower = base.toLowerCase(Locale.ROOT);
                byte[] bytes = readStream(zip.getInputStream(entry));
                if (lower.equals("require.json")) {
                    data.requireJson = bytes;
                } else if (lower.equals("libs.zip")) {
                    // 内层 zip：解出所有 js 库
                    try (ZipInputStream zin = new ZipInputStream(new ByteArrayInputStream(bytes))) {
                        ZipEntry e;
                        while ((e = zin.getNextEntry()) != null) {
                            if (!e.isDirectory()) {
                                String en = e.getName();
                                String eb = en.substring(en.lastIndexOf('/') + 1);
                                if (!eb.startsWith(".") && eb.toLowerCase(Locale.ROOT).endsWith(".js")) {
                                    data.libs.put(eb, readStream(zin));
                                }
                            }
                            zin.closeEntry();
                        }
                    } catch (Exception ignored) {
                    }
                } else if (lower.equals("data.zip")) {
                    // 内层 zip：data/ 下的规则数据文件（plugins/、template/ 等），相对路径原样带出
                    try (ZipInputStream zin = new ZipInputStream(new ByteArrayInputStream(bytes))) {
                        ZipEntry e;
                        while ((e = zin.getNextEntry()) != null) {
                            if (!e.isDirectory()) {
                                String en = e.getName().replace('\\', '/');
                                String eb = en.substring(en.lastIndexOf('/') + 1);
                                // 防路径穿越：跳过绝对路径、含 .. 与隐藏文件条目
                                if (!en.startsWith("/") && !en.contains("..") && !eb.startsWith(".")) {
                                    data.dataFiles.put(en, readStream(zin));
                                }
                            }
                            zin.closeEntry();
                        }
                    } catch (Exception ignored) {
                    }
                } else if (lower.endsWith(".json")) {
                    try {
                        data.rules.addAll(parseJsonList(new String(bytes, StandardCharsets.UTF_8)));
                    } catch (Exception e) {
                        failed.add(base + "（" + e.getMessage() + "）");
                    }
                }
            }
        } catch (ZipException e) {
            throw new IllegalArgumentException("不是有效的 hkzip 包或文件已损坏");
        }
        if (data.rules.isEmpty()) {
            if (!failed.isEmpty()) throw new IllegalArgumentException("hkzip 中没有合法规则：" + failed.get(0));
            throw new IllegalArgumentException("hkzip 中没有找到 rule.json");
        }
        return data;
    }

    /** 把 .hkzip 的附带资源（require.json / libs / data.zip）落盘，供 require() 本地加载与规则读文件。 */
    public void saveHkZipAssets(HkZipData data, List<HkRule> rules) {
        if (data == null || rules == null) return;
        for (HkRule rule : rules) {
            File dataDir = getDataDir(rule.getTitle());
            if (data.requireJson != null) {
                try {
                    Files.write(new File(dataDir, "require.json").toPath(), data.requireJson);
                } catch (Exception ignored) {
                }
            }
            if (!data.libs.isEmpty()) {
                File libsDir = new File(dataDir, "libs");
                libsDir.mkdirs();
                for (Map.Entry<String, byte[]> e : data.libs.entrySet()) {
                    try {
                        Files.write(new File(libsDir, e.getKey()).toPath(), e.getValue());
                    } catch (Exception ignored) {
                    }
                }
            }
            if (!data.dataFiles.isEmpty()) {
                // 8.83 往返：data.zip 内容按包内相对路径落到外部 Documents/data/<规则名>/，
                // 与 hiker://files/data/<规则名>/ 的 getFilePath 映射同根
                File extDataDir = getExternalDataDir(rule.getTitle());
                for (Map.Entry<String, byte[]> e : data.dataFiles.entrySet()) {
                    try {
                        String rel = e.getKey().replace('\\', '/');
                        if (rel.contains("..") || rel.startsWith("/")) continue;
                        File out = new File(extDataDir, rel);
                        File parent = out.getParentFile();
                        if (parent != null) parent.mkdirs();
                        Files.write(out.toPath(), e.getValue());
                    } catch (Exception ignored) {
                    }
                }
            }
        }
    }

    /**
     * 从 .hkzip 包导入：内容为 rule.json + require.json + libs.zip + data.zip。
     * rule.json 走导入；require.json 与 libs.zip 解压到规则数据目录（data/&lt;规则名&gt;/），
     * 供 require() 本地优先加载（无网络时也能用）；data.zip 按包内相对路径解到
     * 外部 Documents/data/&lt;规则名&gt;/（8.83 往返：hiker://files/data/&lt;规则名&gt;/ 可读回）。
     *
     * @return 导入成功的规则列表
     */
    public List<HkRule> importHkZip(File hkzipFile) throws Exception {
        HkZipData data = parseHkZip(hkzipFile);
        List<HkRule> imported = new ArrayList<>();
        for (ParsedRule p : data.rules) {
            saveRule(p.rule, p.json);
            imported.add(p.rule);
        }
        // 落盘 require.json 与 libs，供 require() 本地加载
        saveHkZipAssets(data, imported);
        return imported;
    }

    /** 经典 IO 读流（API 24 可用，不依赖 java.nio）。 */
    private static byte[] readStream(InputStream in) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        in.close();
        return out.toByteArray();
    }

    /**
     * 解析 JSON 文本（不落盘）：兼容单个规则对象与规则数组（一个文件多个小程序）。
     * 数组中单个损坏/不合规的条目只跳过、不影响其它。
     *
     * @return 解析成功的规则列表（单个对象时为 1 个元素的列表）
     * @throws Exception 没有任何合法规则时抛出，message 可直接提示用户
     */
    public List<ParsedRule> parseJsonList(String json) throws Exception {
        if (json == null || json.trim().isEmpty()) throw new IllegalArgumentException("规则内容为空");
        String t = json.trim();
        // 原版 RuleImporterManager 中央分发同款：粘贴文本先识别云口令与分享包装。
        // 用户常把「云Noooole/… 小程序：xxx」整段粘进粘贴导入框；此前直接送 Gson 解析，
        // 报 "Expected BEGIN_OBJECT but was STRING"。只对非 JSON 文本做分发，规则原文不受影响。
        if (!t.startsWith("{") && !t.startsWith("[")) {
            // 1) 云口令（云1~云10）：走云通道（各 Importer.canParse 同款识别，兼容前后缀/多行）
            if (isCloudCode(t)) {
                List<ParsedRule> single = new ArrayList<>();
                single.add(parseByCloudCode(t));
                return single;
            }
            // 2) 分享包装：￥home_rule￥/￥home_rule_v2￥ 明文口令、base64:// 壳
            //    （原版 checkAutoText / importRuleByRuleText 同款）
            String unwrapped = unwrapShareText(t);
            if (unwrapped != null && !unwrapped.equals(t)) {
                t = unwrapped.trim();
            }
        }
        if (!t.startsWith("[")) {
            List<ParsedRule> single = new ArrayList<>();
            HkRule rule = parseJson(t);
            single.add(new ParsedRule(rule, t));
            return single;
        }
        JsonArray arr;
        try {
            arr = JsonParser.parseString(t).getAsJsonArray();
        } catch (Exception e) {
            throw new IllegalArgumentException("规则 JSON 数组解析失败：" + e.getMessage());
        }
        List<ParsedRule> parsed = new ArrayList<>();
        List<String> failed = new ArrayList<>();
        Gson gson = new Gson();
        int idx = 0;
        for (JsonElement el : arr) {
            idx++;
            if (!el.isJsonObject()) {
                failed.add("第" + idx + "项不是对象");
                continue;
            }
            String title = "";
            try {
                if (el.getAsJsonObject().has("title")) title = el.getAsJsonObject().get("title").getAsString();
            } catch (Exception ignored) {
            }
            String label = title.isEmpty() ? "第" + idx + "项" : "「" + title + "」";
            try {
                String raw = gson.toJson(el);
                HkRule rule = parseJson(raw);
                parsed.add(new ParsedRule(rule, raw));
            } catch (Exception e) {
                failed.add(label + "：" + e.getMessage());
            }
        }
        if (parsed.isEmpty()) {
            throw new IllegalArgumentException("没有合法规则" + (failed.isEmpty() ? "" : "，" + failed.get(0)));
        }
        return parsed;
    }

    /**
     * 导入 JSON 文本：兼容单个规则对象与规则数组（一个文件多个小程序）。
     * 数组中单个损坏/不合规的条目只跳过、不影响其它。
     *
     * @return 导入成功的规则列表（单个对象时为 1 个元素的列表）
     * @throws Exception 没有任何合法规则时抛出，message 可直接提示用户
     */
    public List<HkRule> importJsonList(String json) throws Exception {
        List<ParsedRule> parsed = parseJsonList(json);
        List<HkRule> imported = new ArrayList<>();
        for (ParsedRule p : parsed) {
            saveRule(p.rule, p.json);
            imported.add(p.rule);
        }
        return imported;
    }

    /** 是否为 js: 规则文本（以 "js:" 开头，后面是 JS 代码）。 */
    public static boolean isJsRuleText(String text) {
        return text != null && text.trim().startsWith("js:");
    }

    /**
     * 从 JS 规则文本解析：内容以 "js:" 开头，后面是 JS 代码。
     * 包装成 rule.json（type=video，find_rule=js 内容），标题取自文件名。不落盘。
     *
     * @param jsContent js: 开头的规则文本
     * @param fileName  来源文件名（可为 null），用于取标题
     * @return 解析通过的规则
     * @throws Exception 格式错误或校验不通过时抛出
     */
    public ParsedRule parseJsRule(String jsContent, String fileName) throws Exception {
        if (jsContent == null || jsContent.trim().isEmpty()) throw new IllegalArgumentException("JS 规则内容为空");
        String t = jsContent.trim();
        if (!t.startsWith("js:")) throw new IllegalArgumentException("不是 js: 格式的规则");
        String title = fileName;
        if (title != null) {
            int slash = Math.max(title.lastIndexOf('/'), title.lastIndexOf('\\'));
            if (slash >= 0) title = title.substring(slash + 1);
            if (title.toLowerCase(Locale.ROOT).endsWith(".js")) title = title.substring(0, title.length() - 3);
            title = title.trim();
        }
        if (title == null || title.isEmpty()) title = "JS规则";
        JsonObject obj = new JsonObject();
        obj.addProperty("title", title);
        obj.addProperty("type", "video");
        obj.addProperty("url", "");
        obj.addProperty("find_rule", t);
        String json = new Gson().toJson(obj);
        return new ParsedRule(parseJson(json), json);
    }

    /**
     * 从 JS 规则文本导入：内容以 "js:" 开头，后面是 JS 代码。
     * 包装成 rule.json（type=video，find_rule=js 内容），标题取自文件名。
     *
     * @param jsContent js: 开头的规则文本
     * @param fileName  来源文件名（可为 null），用于取标题
     * @return 导入成功的规则
     */
    public HkRule importJsRule(String jsContent, String fileName) throws Exception {
        ParsedRule p = parseJsRule(jsContent, fileName);
        saveRule(p.rule, p.json);
        return p.rule;
    }

    /** 是否为云口令格式（云1~云10 开头；兼容前后缀/多行文本）。 */
    public static boolean isCloudCode(String text) {
        if (text == null) return false;
        return CLOUD_CODE_FIND.matcher(text).find();
    }

    /**
     * 从云口令解析规则（不落盘）：解析口令 → 从云端拉取 rule.json → 解析校验。
     * 导入 UI 用它先解析、弹分组选择器，用户选完后再 saveRule 落盘。
     *
     * @return 解析通过的规则（含原始 JSON）
     * @throws Exception 口令格式错误 / 网络失败 / 内容不是合法规则时抛出
     */
    public ParsedRule parseByCloudCode(String code) throws Exception {
        return parseByCloudCode(code, null);
    }

    /**
     * 带密码版：分享人给云剪贴板设了访问密码时，接收方要把密码带上才能取到内容。
     * password 为空时，若粘贴文本在口令后面还跟了一段（空格分隔），按海阔原版习惯把它当密码用。
     */
    public ParsedRule parseByCloudCode(String code, String password) throws Exception {
        if (code == null || code.trim().isEmpty()) throw new IllegalArgumentException("口令为空");
        // 先从文本中提取口令子串（兼容"云6oooole/xxxxxx/xxx 小程序：yyy"这类带后缀的输入）
        Matcher f = CLOUD_CODE_FIND.matcher(code);
        if (!f.find()) throw new IllegalArgumentException("不是有效的云口令（需云1~云10开头）");
        String raw = f.group();
        Matcher m = CLOUD_CODE_PATTERN.matcher(raw);
        if (!m.matches()) throw new IllegalArgumentException("不是有效的云口令（需云1~云10开头）");
        String version = m.group(1);
        String path = m.group(2); // 如 /p/xxxx、/apidb/yyyy、/xxxxxx/pppp、/f7c6
        String pwd = password;
        if ((pwd == null || pwd.isEmpty()) && f.end() < code.length()) {
            // 口令后空格跟的一段当密码（海阔 PastemeImporter 同款切法）；"小程序：xxx"这类说明后缀不算
            String tail = code.substring(f.end()).trim();
            if (!tail.isEmpty() && !tail.startsWith("小程序")) {
                String token = tail.split("\\s+")[0];
                if (!token.isEmpty() && !token.startsWith("http")) pwd = token;
            }
        }
        String json = fetchCloudJson(path, version, pwd);
        if (json == null || json.trim().isEmpty()) throw new IllegalArgumentException("云端返回内容为空");
        String t = json.trim();
        return new ParsedRule(parseJson(t), t);
    }

    /**
     * 取云剪贴板粘贴文本原文（不做规则校验）：8.83 parsePaste →
     * RuleImporterManager.parseSync 语义，返回通道里的原文内容。聚合类（聚阅）口令的
     * 内容是接口列表文本而非规则 JSON，importByCloudCode 按规则解析失败后走这里把
     * 原文还给 JS。取不到原文返回 null，调用方回退返回口令原文。
     */
    public String fetchPasteRawText(String code) {
        try {
            Matcher f = CLOUD_CODE_FIND.matcher(code);
            if (!f.find()) return null;
            Matcher m = CLOUD_CODE_PATTERN.matcher(f.group());
            if (!m.matches()) return null;
            String version = m.group(1);
            String path = m.group(2);
            // 云5 → cmd.im：取 test_box 便签原文（与 extractRuleJsonFromCmdIm 同款提取，不做规则剥壳）
            if ("5".equals(version)) {
                String id = path.substring(1).split("[\\s/]")[0];
                if (id.isEmpty()) return null;
                OkHttpClient client = HkHttp.client();
                Request req = new Request.Builder().url(CMDIM_BASE + "/" + id)
                        .header("User-Agent", "Mozilla/5.0").build();
                try (Response resp = client.newCall(req).execute()) {
                    if (!resp.isSuccessful() || resp.body() == null) return null;
                    Matcher mm = Pattern.compile("class=\"test_box\"[^>]*>(.*?)</div>",
                            Pattern.DOTALL).matcher(resp.body().string());
                    if (!mm.find()) return null;
                    String text = mm.group(1).replaceAll("<[^>]+>", "").trim()
                            .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
                            .replace("&quot;", "\"").replace("&#39;", "'");
                    return text.isEmpty() ? null : text;
                }
            }
            // 其它通道暂不单独取原文：返回 null → 调用方回退口令原文
        } catch (Throwable ignored) {
        }
        return null;
    }

    /**
     * 从云口令导入规则：解析口令 → 从云端拉取 rule.json → 走 importJson 落盘。
     *
     * @return 导入成功的规则
     * @throws Exception 口令格式错误 / 网络失败 / 内容不是合法规则时抛出
     */
    public HkRule importByCloudCode(String code) throws Exception {
        ParsedRule p = parseByCloudCode(code);
        saveRule(p.rule, p.json);
        return p.rule;
    }

    /** 按口令 path 从云端拉取 rule.json 文本（通道按官方 App 逆向结果路由）。 */
    /** 云6 tyrantg 剪贴板取一次：成功返回规则 JSON，失败（不存在/需密码/密码错）返回 null。 */
    private String fetchPasteme(OkHttpClient client, String rest) throws Exception {
        Request req = new Request.Builder().url(PASTEME_API + rest)
                .header("referer", PASTEME_REFERER)
                .header("cookie", "")
                .header("User-Agent", "Mozilla/5.0").build();
        try (Response resp = client.newCall(req).execute()) {
            if (!resp.isSuccessful() || resp.body() == null) return null;
            return extractRuleJsonFromPasteme(resp.body().string());
        }
    }

    private String fetchCloudJson(String path, String version, String password) throws Exception {
        // 8.83 BailanImporter 用 DohProvidersKt.buildAutoClient(url)：走 App 的 DoH 客户端。
        // 裸 OkHttpClient 与海阔配置不一致，改走 HkHttp.client()（CookieJar/拦截器；DNS 系统默认照原版）。
        OkHttpClient client = HkHttp.client();
        // 1) /apidb/{key} → textdb.online（官方云2分享通道，已验证可用）
        if (path.startsWith("/apidb/")) {
            String key = path.substring("/apidb/".length()).split("[\\s/]")[0];
            if (key.isEmpty()) throw new IllegalArgumentException("口令中 apidb key 为空");
            Request req = new Request.Builder().url(TEXTDB_GET + key)
                    .header("User-Agent", "Mozilla/5.0").build();
            try (Response resp = client.newCall(req).execute()) {
                if (!resp.isSuccessful() || resp.body() == null)
                    throw new IllegalArgumentException("云端请求失败（HTTP " + resp.code() + "）");
                String b64 = resp.body().string().trim();
                // 原版 NetCutImporter.fromSafeBase64：解开外层只要求是合法 base64，不校验是否为规则原文；
                // 分享内容可能是「说明文本＋base64://@标题@真规则」包装，剥壳走 unwrapShareText（原版分发器同款）。
                String outer = decodeBase64Loose(b64);
                if (outer == null) throw new IllegalArgumentException("云端内容不是有效的 base64 规则");
                String json = unwrapShareText(outer);
                if (json == null || json.isEmpty()) throw new IllegalArgumentException("云端内容不是有效的 base64 规则");
                return json;
            }
        }
        // 2) /xxxxxx/{path}(@{pwd}) → pasteme.tyrantg.com（官方云6通道，白阑剪贴板，已验证可用）
        if (path.startsWith("/xxxxxx/")) {
            String rest = path.substring("/xxxxxx/".length()).split("\\s")[0];
            if (rest.isEmpty()) throw new IllegalArgumentException("口令中缺少便签ID");
            String json = fetchPasteme(client, rest);
            if (json == null && password != null && !password.isEmpty() && !rest.contains("(@")) {
                // 分享人设了访问密码：按 (@密码) 后缀再取一次
                json = fetchPasteme(client, rest + "(@" + password + ")");
            }
            if (json == null) throw new IllegalArgumentException("云端未返回有效规则（便签不存在或密码错误）");
            return json;
        }
        // 3) 云5 → cmd.im（官方云5通道，已验证可用）
        if ("5".equals(version)) {
            String id = path.substring(1).split("[\\s/]")[0];
            if (id.isEmpty()) throw new IllegalArgumentException("口令中缺少便签ID");
            Request req = new Request.Builder().url(CMDIM_BASE + "/" + id)
                    .header("User-Agent", "Mozilla/5.0").build();
            try (Response resp = client.newCall(req).execute()) {
                if (!resp.isSuccessful() || resp.body() == null)
                    throw new IllegalArgumentException("云端请求失败（HTTP " + resp.code() + "）");
                String json = extractRuleJsonFromCmdIm(resp.body().string());
                if (json == null) throw new IllegalArgumentException("云端未返回有效规则（便签不存在或内容格式不对）");
                return json;
            }
        }
        // 4) 云1 → pastebin.com（官方云1通道）：云1oooole/raw/{pasteId}
        //    8.83 PastebinImporter.parse 原文：code.replace("云1oooole","https://pastebin.com")，
        //    以 https://pastebin.com/raw/ 开头则直接 GET。
        if ("1".equals(version)) {
            String id = path.startsWith("/raw/") ? path.substring("/raw/".length()) : path.substring(1);
            id = id.split("[\\s/]")[0];
            if (id.isEmpty()) throw new IllegalArgumentException("口令中缺少 pasteId");
            Request req = new Request.Builder().url("https://pastebin.com/raw/" + id)
                    .header("User-Agent", "Mozilla/5.0").build();
            try (Response resp = client.newCall(req).execute()) {
                if (!resp.isSuccessful() || resp.body() == null)
                    throw new IllegalArgumentException("云端请求失败（HTTP " + resp.code() + "）");
                String json = unwrapShareText(resp.body().string());
                if (json == null || json.isEmpty()) throw new IllegalArgumentException("云端未返回有效规则");
                return json;
            }
        }
        // 5) 云7 → note.ms（官方云7通道）：云7oooole/{note}
        //    8.83 NotesImporter.parseSync 原文：parseDomForUrl(url, "textarea&&Text", "UTF-8")。
        if ("7".equals(version)) {
            String id = path.substring(1).split("[\\s/]")[0];
            if (id.isEmpty()) throw new IllegalArgumentException("口令中缺少便签ID");
            Request req = new Request.Builder().url("https://note.ms/" + id)
                    .header("User-Agent", "Mozilla/5.0").build();
            try (Response resp = client.newCall(req).execute()) {
                if (!resp.isSuccessful() || resp.body() == null)
                    throw new IllegalArgumentException("云端请求失败（HTTP " + resp.code() + "）");
                String json = unwrapShareText(extractTextarea(resp.body().string()));
                if (json == null || json.isEmpty()) throw new IllegalArgumentException("云端未返回有效规则");
                return json;
            }
        }
        // 6) 云9 → txtpad.cn（官方云9通道）：云9oooole/{id}
        //    8.83 TxtPadImporter.parse 原文：code.replace("云9oooole","https://txtpbbd.cn") 后拉取。
        if ("9".equals(version)) {
            String id = path.substring(1).split("[\\s/]")[0];
            if (id.isEmpty()) throw new IllegalArgumentException("口令中缺少便签ID");
            Request req = new Request.Builder().url("https://txtpad.cn/" + id)
                    .header("User-Agent", "Mozilla/5.0").build();
            try (Response resp = client.newCall(req).execute()) {
                if (!resp.isSuccessful() || resp.body() == null)
                    throw new IllegalArgumentException("云端请求失败（HTTP " + resp.code() + "）");
                String json = unwrapShareText(resp.body().string());
                if (json == null || json.isEmpty()) throw new IllegalArgumentException("云端未返回有效规则");
                return json;
            }
        }
        // 7) 云10 → hastebin（官方云10通道）：云10oooole/share/a/b/{key}
        //    8.83 HastebinImporter 原文用 https://hassdtebin.com/share/a/b/ 分享。
        if ("10".equals(version)) {
            String[] segs = path.split("/");
            String key = segs[segs.length - 1].split("\\s")[0];
            if (key.isEmpty()) throw new IllegalArgumentException("口令中缺少 key");
            String[] bases = {"https://hassdtebin.com/raw/", "https://hastebin.com/raw/"};
            for (String base : bases) {
                try {
                    Request req = new Request.Builder().url(base + key)
                            .header("User-Agent", "Mozilla/5.0").build();
                    try (Response resp = client.newCall(req).execute()) {
                        if (!resp.isSuccessful() || resp.body() == null) continue;
                        String json = unwrapShareText(resp.body().string());
                        if (json != null && !json.isEmpty()) return json;
                    }
                } catch (Exception ignored) {
                }
            }
            throw new IllegalArgumentException("云端未返回有效规则");
        }
        // 8) /p/{note_id} → netcut.cn：官方已下线该接口，通道失效，给明确提示
        if (path.startsWith("/p/")) {
            throw new IllegalArgumentException("该口令走 netcut 通道，netcut 已停止开放接口，无法拉取。"
                    + "请让分享者用「导出为云口令」重新分享，或索取规则文件导入。");
        }
        throw new IllegalArgumentException("暂不支持该云口令通道（当前支持：云1、云2/apidb、云5、云6、云7、云9、云10）");
    }

    /** 从 note.ms 页面中提取 textarea 文本（8.83 NotesImporter 用 "textarea&&Text" 选择器）。 */
    private String extractTextarea(String html) {
        if (html == null) return null;
        try {
            Matcher m = Pattern.compile("<textarea[^>]*>(.*?)</textarea>",
                    Pattern.DOTALL | Pattern.CASE_INSENSITIVE).matcher(html);
            if (!m.find()) return null;
            String text = m.group(1).replaceAll("<[^>]+>", "").trim();
            text = text.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
                    .replace("&quot;", "\"").replace("&#39;", "'");
            return text.isEmpty() ? null : text;
        } catch (Exception ignored) {
            return null;
        }
    }

    /** 从 pasteme API 响应中提取规则内容：{"return_code":0,"data":"...rule.json..."}。 */
    private String extractRuleJsonFromPasteme(String text) {
        if (text == null) return null;
        try {
            JsonObject obj = JsonParser.parseString(text.trim()).getAsJsonObject();
            if (obj.has("return_code") && obj.get("return_code").getAsInt() != 0) return null;
            if (!obj.has("data") || obj.get("data").isJsonNull()) return null;
            String data = obj.get("data").getAsString().trim();
            if (data.isEmpty()) return null;
            return unwrapShareText(data);
        } catch (Exception ignored) {
        }
        return null;
    }

    /** 从 cmd.im 页面中提取 .test_box 文本（规则内容）。 */
    private String extractRuleJsonFromCmdIm(String html) {
        if (html == null) return null;
        try {
            Matcher m = Pattern.compile("class=\"test_box\"[^>]*>(.*?)</div>", Pattern.DOTALL).matcher(html);
            if (!m.find()) return null;
            String text = m.group(1).replaceAll("<[^>]+>", "").trim();
            // HTML 转义还原
            text = text.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
                    .replace("&quot;", "\"").replace("&#39;", "'");
            if (text.isEmpty()) return null;
            return unwrapShareText(text);
        } catch (Exception ignored) {
        }
        return null;
    }

    /**
     * 还原分享包装：内容可能是 ￥home_rule￥{json} 明文口令（海阔官方格式），
     * 也可能是 ￥…￥base64://@标题@BASE64 包装（cmd.im 常见），
     * 也可能是裸 rule.json / 规则数组 / js: 文本。
     */
    private String unwrapShareText(String text) {
        if (text == null) return null;
        String t = text.trim();
        // 海阔明文口令：…￥home_rule￥{json} / ￥home_rule_v2￥{json}
        String unwrapped = unwrapHomeRuleCommand(t);
        if (unwrapped != null) return unwrapped;
        int idx = t.indexOf("base64://");
        if (idx >= 0) {
            // base64://@标题@BASE64 → 取 @ 分隔的第 3 段解码（官方 importRuleByRuleText 同款）
            String[] segs = t.substring(idx + "base64://".length()).split("@");
            if (segs.length >= 3) {
                String decoded = safeBase64Decode(segs[2].replaceAll("\\s+", ""));
                if (decoded != null) return decoded;
            }
            return null;
        }
        return t;
    }

    /**
     * 解析海阔明文口令：…￥home_rule￥{json} / ￥home_rule_v2￥{json}。
     * 兼容裸 JSON（以 { 开头且含 col_type 和 find_rule，直接视为规则）。
     * 成功返回规则 JSON，失败返回 null。
     */
    public static String unwrapHomeRuleCommand(String text) {
        if (text == null) return null;
        String t = text.trim();
        // 裸 JSON：海阔 realCheckAutoText 同款判断
        if (t.startsWith("{") && t.endsWith("}") && t.contains("col_type") && t.contains("find_rule")) {
            return t;
        }
        // ￥type￥json 格式：按 ￥ 切分，至少 3 段，取第 3 段及之后拼回（json 内可能含 ￥）
        if (!t.contains("\uFFE5")) return null;
        String[] parts = t.split("\uFFE5", -1);
        if (parts.length < 3) return null;
        String type = parts[1].trim();
        if (!"home_rule".equals(type) && !"home_rule_v2".equals(type)) return null;
        StringBuilder sb = new StringBuilder();
        for (int i = 2; i < parts.length; i++) {
            if (i > 2) sb.append("\uFFE5");
            sb.append(parts[i]);
        }
        String json = sb.toString().trim();
        if (json.startsWith("{") && json.contains("col_type")) return json;
        return null;
    }

    /** 是否为海阔分享口令文本（明文口令或裸规则 JSON）。 */
    public static boolean isShareCommandText(String text) {
        return unwrapHomeRuleCommand(text) != null;
    }

    /**
     * 生成海阔明文口令：海阔视界规则分享，当前分享的是：小程序￥home_rule￥{json}。
     * 与 8.83 AutoImportHelper.getCommand("home_rule") 格式一致，可被海阔官方识别导入。
     */
    public static String buildShareCommand(String ruleJson) {
        if (ruleJson == null) return null;
        return "\u6D77\u9614\u89C6\u754C\u89C4\u5219\u5206\u4EAB\uFF0C\u5F53\u524D\u5206\u4EAB\u7684\u662F\uFF1A\u5C0F\u7A0B\u5E8F\uFFE5home_rule\uFFE5" + ruleJson.trim();
    }

    /** 兼容标准 / URL 安全两种 base64 的解码，失败返回 null。 */
    private String safeBase64Decode(String b64) {
        String decoded = decodeBase64Loose(b64);
        if (decoded != null && decoded.contains("{") && decoded.contains("}")) return decoded;
        return null;
    }

    /** base64 解码（宽松版）：只要求是合法 base64，不校验解码后是否为规则原文；原版 fromSafeBase64 用 URL_SAFE|NO_WRAP。 */
    private String decodeBase64Loose(String b64) {
        if (b64 == null) return null;
        String s = b64.trim().replaceAll("\\s+", "");
        // URL 安全字符集（-/_）先归一化为标准字符集（+/）：安卓 Base64.DEFAULT 是宽松解码，
        // 遇到 -/_ 不抛异常而是静默跳过，会产出垃圾字符串且不再尝试 URL_SAFE（云2/apidb 的 seeduck 回包即此坑）。
        // 归一化后标准/URL安全/混用三种都能吃，比原版更宽。
        if (s.indexOf('-') >= 0 || s.indexOf('_') >= 0) {
            s = s.replace('-', '+').replace('_', '/');
        }
        // 补齐 padding
        int mod = s.length() % 4;
        if (mod == 1) return null;
        String padded = s + "====".substring(0, (4 - mod) % 4);
        int[] flags = {Base64.DEFAULT, Base64.URL_SAFE, Base64.NO_WRAP};
        for (int flag : flags) {
            try {
                byte[] bytes = Base64.decode(padded, flag);
                return new String(bytes, StandardCharsets.UTF_8);
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    /**
     * 导出规则为云口令（textdb 通道）：rule.json → base64 → 上传 textdb.online
     * → 返回 云2oooole/apidb/{key}（与官方 App 的云2分享格式一致，官方 App 可导入）。
     *
     * @return 生成的云口令
     */
    public String exportAsCloudCode(String title) throws Exception {
        File f = new File(getDir(), safeFileName(title) + ".json");
        if (!f.exists()) throw new IllegalArgumentException("规则不存在：" + title);
        byte[] bytes = Files.readAllBytes(f.toPath());
        String json = new String(bytes, StandardCharsets.UTF_8);
        String b64 = Base64.encodeToString(json.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
        String key = "xc" + System.currentTimeMillis() / 1000 + "_" + Math.abs(title.hashCode());

        OkHttpClient client = new OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
                .build();
        FormBody body = new FormBody.Builder()
                .add("key", key)
                .add("value", b64)
                .build();
        Request req = new Request.Builder().url(TEXTDB_API_UPDATE).post(body)
                .header("User-Agent", "Mozilla/5.0").build();
        try (Response resp = client.newCall(req).execute()) {
            if (!resp.isSuccessful() || resp.body() == null)
                throw new IllegalArgumentException("上传失败（HTTP " + resp.code() + "）");
            String respText = resp.body().string();
            try {
                JsonObject obj = JsonParser.parseString(respText).getAsJsonObject();
                if (obj.has("status") && obj.get("status").getAsInt() == 1) {
                    return "云2oooole/apidb/" + key;
                }
            } catch (Exception ignored) {
            }
            throw new IllegalArgumentException("上传失败：服务器返回异常");
        }
    }

    /**
     * 导出规则为云5短口令（cmd.im 通道）：POST rule.json → 302 跳转取短 ID
     * → 返回 云5oooole/{id}（短格式，与官方 App 的云5分享格式一致，官方 App 可导入）。
     *
     * @return 生成的云口令
     */
    public String exportAsCmdImCode(String title) throws Exception {
        File f = new File(getDir(), safeFileName(title) + ".json");
        if (!f.exists()) throw new IllegalArgumentException("规则不存在：" + title);
        byte[] bytes = Files.readAllBytes(f.toPath());
        String json = new String(bytes, StandardCharsets.UTF_8);

        OkHttpClient client = new OkHttpClient.Builder()
                .followRedirects(false)
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
                .build();
        FormBody body = new FormBody.Builder().add("txt", json).build();
        Request req = new Request.Builder().url(CMDIM_BASE + "/").post(body)
                .header("Origin", CMDIM_BASE)
                .header("Referer", CMDIM_BASE + "/")
                .header("User-Agent", "Mozilla/5.0").build();
        try (Response resp = client.newCall(req).execute()) {
            String loc = resp.header("Location");
            if (loc == null || loc.isEmpty())
                throw new IllegalArgumentException("上传失败：未返回短链接（HTTP " + resp.code() + "）");
            // Location 形如 /8xwm
            String id = loc.substring(loc.lastIndexOf('/') + 1).split("[\\s?]")[0];
            if (id.isEmpty()) throw new IllegalArgumentException("上传失败：短链接格式异常");
            return "云5oooole/" + id;
        }
    }

    /**
     * 导出规则为文件：复制 rule.json 到指定目录（默认 Download）。
     *
     * @return 导出的文件
     */
    public File exportAsFile(String title, File destDir) throws Exception {
        File src = new File(getDir(), safeFileName(title) + ".json");
        if (!src.exists()) throw new IllegalArgumentException("规则不存在：" + title);
        if (destDir != null && !destDir.exists()) destDir.mkdirs();
        File dest = new File(destDir != null ? destDir : getDir(),
                safeFileName(title) + ".hkrule.json");
        byte[] bytes = Files.readAllBytes(src.toPath());
        Files.write(dest.toPath(), bytes);
        return dest;
    }

    /**
     * 导出规则为 .hkzip 包：rule.json + require.json（若有）+ libs.zip（若有附带库）。
     *
     * @return 导出的文件
     */
    public File exportAsHkZip(String title, File destDir) throws Exception {
        return exportRulePackage(title, destDir, ".hkzip");
    }

    /**
     * 导出规则为 .zip 包：内容与 .hkzip 完全相同（rule.json + require.json + libs.zip），仅后缀为 .zip。
     * 海阔里常见"xxx.zip"规则包（如"黄色仓库.zip"），与 .hkzip 互通，导入走同一套解析。
     *
     * @return 导出的文件
     */
    public File exportAsZip(String title, File destDir) throws Exception {
        return exportRulePackage(title, destDir, ".zip");
    }

    /** 导出规则包的公共实现：ext 决定后缀（.hkzip / .zip），包内容一致。 */
    private File exportRulePackage(String title, File destDir, String ext) throws Exception {
        File src = new File(getDir(), safeFileName(title) + ".json");
        if (!src.exists()) throw new IllegalArgumentException("规则不存在：" + title);
        if (destDir != null && !destDir.exists()) destDir.mkdirs();
        File dest = new File(destDir != null ? destDir : getDir(), safeFileName(title) + ext);
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(dest.toPath()))) {
            writeZipEntry(zos, "rule.json", Files.readAllBytes(src.toPath()));
            File dataDir = getDataDir(title);
            File require = new File(dataDir, "require.json");
            if (require.exists()) writeZipEntry(zos, "require.json", Files.readAllBytes(require.toPath()));
            File libsDir = new File(dataDir, "libs");
            File[] libs = libsDir.isDirectory() ? libsDir.listFiles() : null;
            if (libs != null && libs.length > 0) {
                ByteArrayOutputStream libsZip = new ByteArrayOutputStream();
                try (ZipOutputStream lzos = new ZipOutputStream(libsZip)) {
                    for (File f : libs) {
                        if (f.isFile()) writeZipEntry(lzos, f.getName(), Files.readAllBytes(f.toPath()));
                    }
                }
                writeZipEntry(zos, "libs.zip", libsZip.toByteArray());
            }
        }
        return dest;
    }

    private void writeZipEntry(ZipOutputStream zos, String name, byte[] bytes) throws Exception {
        ZipEntry entry = new ZipEntry(name);
        zos.putNextEntry(entry);
        zos.write(bytes);
        zos.closeEntry();
    }

    /** 列出所有已导入规则（损坏的文件跳过，不影响整体；异常写入文件日志方便定位）。 */
    public List<HkRule> getRules() {
        List<HkRule> list = new ArrayList<>();
        File dir = getDir();
        File[] files = dir.listFiles((d, name) -> name.endsWith(".json"));
        if (files == null) return list;
        Gson gson = new Gson();
        // 同标题去重：保留最后落盘（mtime 最新）的那份，平局时标准文件名优先。
        // 旧口径「保留 listFiles 第一个」顺序不确定：目录里只要有同标题旧副本，
        // 就可能一直读旧的——导入新版看着没效果。此处只定取舍，物理清理由 saveRule 做。
        java.util.Map<String, HkRule> best = new LinkedHashMap<>();
        java.util.Map<String, Long> bestMtime = new java.util.HashMap<>();
        java.util.Map<String, Boolean> bestCanonical = new java.util.HashMap<>();
        for (File f : files) {
            try {
                byte[] bytes = Files.readAllBytes(f.toPath());
                HkRule rule = gson.fromJson(new String(bytes, StandardCharsets.UTF_8), HkRule.class);
                if (rule == null) continue;
                // 标题脏数据（空/"undefined"/"null"）时用文件名回退，保证 tab 有名可显示
                if (rule.getTitle().isEmpty()) {
                    String fn = f.getName();
                    if (fn.endsWith(".json")) fn = fn.substring(0, fn.length() - 5);
                    if (!fn.isEmpty() && !"unnamed".equals(fn)) rule.setTitle(fn);
                }
                String title = rule.getTitle();
                boolean canonical = f.getName().equals(safeFileName(title) + ".json");
                long mtime = f.lastModified();
                HkRule cur = best.get(title);
                if (cur != null) {
                    long curMtime = bestMtime.get(title);
                    boolean curCanonical = Boolean.TRUE.equals(bestCanonical.get(title));
                    boolean newer = mtime > curMtime
                            || (mtime == curMtime && canonical && !curCanonical);
                    if (!newer) continue;
                }
                rule.setEnabled(isEnabled(title));
                best.put(title, rule);
                bestMtime.put(title, mtime);
                bestCanonical.put(title, canonical);
            } catch (Exception e) {
                e.printStackTrace();
                logRuleError(f.getName(), e);
            }
        }
        list.addAll(best.values());
        return list;
    }

    /** 规则解析失败写入文件日志（/files/hk_rule_error.log），真机可查看定位。 */
    private void logRuleError(String fileName, Exception e) {
        try {
            Context ctx = appContext();
            if (ctx == null) return;
            File log = new File(ctx.getFilesDir(), "hk_rule_error.log");
            String msg = new java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(new java.util.Date())
                    + " parse failed: " + fileName + " | " + e.getClass().getSimpleName() + ": " + e.getMessage() + "\n";
            java.nio.file.Files.write(log.toPath(), msg.getBytes(StandardCharsets.UTF_8),
                    java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
        } catch (Exception ignored) {
        }
    }

    /** 按标题取规则（标题即文件名）。 */
    public HkRule getRule(String title) {
        if (title == null) return null;
        File f = new File(getDir(), safeFileName(title) + ".json");
        if (!f.exists()) return null;
        try {
            byte[] bytes = Files.readAllBytes(f.toPath());
            HkRule rule = new Gson().fromJson(new String(bytes, StandardCharsets.UTF_8), HkRule.class);
            if (rule != null) rule.setEnabled(isEnabled(rule.getTitle()));
            return rule;
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    /** 规则文件最后修改时间（播放路由缓存失效判断用），文件不存在返回 -1。 */
    public long ruleModified(String title) {
        if (title == null) return -1;
        File f = new File(getDir(), safeFileName(title) + ".json");
        return f.exists() ? f.lastModified() : -1;
    }

    /** 删除规则（含启用开关记录与规则数据目录：kv/自带库都在里面，
     *  不清的话坏缓存删规则重装也甩不掉，UU视频实证）。 */
    public boolean delete(String title) {
        if (title == null) return false;
        File f = new File(getDir(), safeFileName(title) + ".json");
        boolean ok = !f.exists() || f.delete();
        if (ok) SAVE_STAMP.incrementAndGet();
        try {
            deleteRecursive(getDataDir(title));
        } catch (Throwable ignored) {
        }
        try {
            Context ctx = appContext();
            if (ctx != null) ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit().remove(KEY_ENABLED_PREFIX + title).apply();
        } catch (Exception e) {
            e.printStackTrace();
        }
        return ok;
    }

    private static void deleteRecursive(File f) {
        if (f == null || !f.exists()) return;
        File[] kids = f.isDirectory() ? f.listFiles() : null;
        if (kids != null) for (File k : kids) deleteRecursive(k);
        f.delete();
    }

    public void setEnabled(String title, boolean enabled) {
        if (title == null) return;
        try {
            Context ctx = appContext();
            if (ctx != null) {
                SharedPreferences sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
                sp.edit().putBoolean(KEY_ENABLED_PREFIX + title, enabled).apply();
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /** 默认启用；只有用户手动关闭后才为 false。 */
    public boolean isEnabled(String title) {
        try {
            Context ctx = appContext();
            if (ctx == null) return true;
            return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .getBoolean(KEY_ENABLED_PREFIX + title, true);
        } catch (Exception e) {
            e.printStackTrace();
            return true;
        }
    }

    /** 只返回启用中的规则。 */
    public List<HkRule> getEnabledRules() {
        List<HkRule> all = getRules();
        List<HkRule> enabled = new ArrayList<>();
        for (HkRule r : all) if (r.isEnabled()) enabled.add(r);
        return enabled;
    }

    /** 文件名安全化：去非法字符，防路径穿越。 */
    private static String safeFileName(String title) {
        if (title == null) return "unnamed";
        String s = title.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
        if (s.isEmpty()) s = "unnamed";
        if (s.length() > 80) s = s.substring(0, 80);
        return s;
    }
}
