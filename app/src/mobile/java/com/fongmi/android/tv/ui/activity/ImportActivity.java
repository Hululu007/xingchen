package com.fongmi.android.tv.ui.activity;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Parcelable;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.fongmi.android.tv.api.config.VodConfig;
import com.fongmi.android.tv.api.loader.BaseLoader;
import com.fongmi.android.tv.api.music.MusicSourceManager;
import com.fongmi.android.tv.bean.Site;
import com.fongmi.android.tv.event.RefreshEvent;
import com.fongmi.android.tv.utils.PyExtConfig;
import com.fongmi.android.tv.utils.Task;
import com.fongmi.android.tv.utils.ToastUtil;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * .py/.js「打开方式」总门（清单三份申报的落点）：
 * 外部点开脚本文件 → 静默登记进插件管理 → 直接打开这个脚本的内容。
 * 成功全程零打扰（不弹页、不出提示），用户感知就是「点开文件→脚本开了」；
 * 只有真失败（读不到／定不了型／没存进去）才 Toast 一句原因。
 *
 * 登记口径与插件管理手工导入完全一致（同一套存储，不另起炉灶）：
 * 文件落 files/plugins/py|js，目录即注册表（PluginActivity 扫目录成列，
 * PyLoader/JsLoader 监听同目录），失效照 PluginActivity.invalidateFiles
 * （py 按文件单清、js 全清）。
 * 打开走现行选源路，不另写跳转：照 VodFragment.onScriptSelected 建动态源
 * （key py_/js_+基名、api 文件绝对路径、type 3、ext 走 PyExtConfig），
 * registerDynamicSite（CollectFragment 先例）＋ setHome ＋ RefreshEvent.home，
 * 再把 HomeActivity 拉到前台，分类/列表随首页自家逻辑出来。
 * 音乐插件形态的 .js（MusicSourceManager.detectFormat 认得出）没有点播
 * 「源首页」概念：文件照样登记进插件管理，打开落音乐插件管理页——但它
 * 不会出现在音乐插件列表里（音乐侧是另一套登记：MusicSourceManager 按
 * srcUrl/本地副本入库，本门不往那套里塞，冲突与理由记在导入账里）。
 */
public class ImportActivity extends AppCompatActivity {

    /** 导入体积上限：脚本源常见几十~几百 KB，10MB 足够兜住胖源、又挡住误点的大文件 */
    private static final long MAX_BYTES = 10L * 1024 * 1024;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Uri uri = resolveUri(getIntent());
        if (uri == null) {
            finish();
            return;
        }
        Task.execute(() -> doImport(uri));
    }

    /** VIEW 取 data；SEND 取 EXTRA_STREAM；其余一律不接 */
    private Uri resolveUri(Intent intent) {
        if (intent == null) return null;
        if (Intent.ACTION_VIEW.equals(intent.getAction())) return intent.getData();
        if (Intent.ACTION_SEND.equals(intent.getAction())) {
            Parcelable p = intent.getParcelableExtra(Intent.EXTRA_STREAM);
            if (p instanceof Uri) return (Uri) p;
        }
        return null;
    }

    private void doImport(Uri uri) {
        String displayName = queryDisplayName(uri);
        byte[] bytes;
        try {
            bytes = readAll(uri);
        } catch (TooLargeException e) {
            fail("文件太大了，超过 10MB 没法导入");
            return;
        } catch (Exception e) {
            fail("读不到这个文件");
            return;
        }
        if (bytes == null || bytes.length == 0) {
            fail("文件是空的");
            return;
        }
        PluginFileJudge.Verdict verdict = PluginFileJudge.judge(displayName, bytes);
        if (verdict.kind == PluginFileJudge.Kind.UNKNOWN) {
            fail("认不出这是哪种脚本");
            return;
        }
        boolean py = verdict.kind == PluginFileJudge.Kind.PY;
        String targetName = verdict.targetName != null ? verdict.targetName
                : "import_" + System.currentTimeMillis() + (py ? ".py" : ".js");
        File dir = new File(getFilesDir(), py ? "plugins/py" : "plugins/js");
        File dest = new File(dir, targetName);
        try {
            landFile(dir, dest, bytes);
        } catch (Exception e) {
            fail("没存进去，导入失败");
            return;
        }
        // 登记即落盘（目录即注册表）；同名覆盖后照插件管理口径失效旧蜘蛛
        try {
            if (py) BaseLoader.get().invalidatePyScript(dest.getAbsolutePath());
            else BaseLoader.get().clearJs();
        } catch (Throwable ignored) {
        }
        RefreshEvent.home();
        runOnUiThread(() -> openImported(dest, py, bytes));
    }

    /** 先写临时文件再改名（同名覆盖原子换，不留半截文件）；改名不成退回直写 */
    private void landFile(File dir, File dest, byte[] bytes) throws Exception {
        if (!dir.exists() && !dir.mkdirs()) throw new IllegalStateException("mkdirs");
        File tmp = new File(dir, dest.getName() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(bytes);
        }
        if (!tmp.renameTo(dest)) {
            try (FileOutputStream out = new FileOutputStream(dest)) {
                out.write(bytes);
            }
            tmp.delete();
        }
    }

    private void openImported(File dest, boolean py, byte[] bytes) {
        if (!py) {
            // 音乐插件类没有点播源首页：打开落它现行的管理页（登记仍在插件管理，见类头注）
            String format = null;
            try {
                format = MusicSourceManager.detectFormat(new String(bytes, StandardCharsets.UTF_8));
            } catch (Throwable ignored) {
            }
            if (format != null) {
                MusicPluginsActivity.startSites(this);
                finish();
                return;
            }
        }
        String fileName = dest.getName();
        String base = fileName.contains(".") ? fileName.substring(0, fileName.lastIndexOf('.')) : fileName;
        String prefix = py ? "py_" : "js_";
        try {
            Site site = new Site();
            site.setKey(prefix + base);
            site.setName(base);
            site.setApi(dest.getAbsolutePath());
            site.setType(3);
            String savedExt = PyExtConfig.load(this, prefix + base);
            site.setExt(savedExt == null || savedExt.isEmpty() ? "{}" : savedExt);
            site.setJar("");
            VodConfig.get().registerDynamicSite(site);
            VodConfig.get().setHome(site);
        } catch (Throwable ignored) {
        }
        RefreshEvent.home();
        startActivity(new Intent(this, HomeActivity.class));
        finish();
    }

    /** 失败才出声：一句原因＋收尾。成功路径永不走这里 */
    private void fail(String reason) {
        runOnUiThread(() -> {
            ToastUtil.show(this, reason, Toast.LENGTH_LONG);
            finish();
        });
    }

    private static class TooLargeException extends Exception {
    }

    private byte[] readAll(Uri uri) throws Exception {
        try (InputStream in = getContentResolver().openInputStream(uri)) {
            if (in == null) throw new IllegalStateException("openInputStream null");
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int len;
            while ((len = in.read(chunk)) > 0) {
                if (buf.size() + len > MAX_BYTES) throw new TooLargeException();
                buf.write(chunk, 0, len);
            }
            return buf.toByteArray();
        }
    }

    /** 文件名：先问 DISPLAY_NAME，没有再退 lastPathSegment（照 PluginActivity.getFileName 口径） */
    private String queryDisplayName(Uri uri) {
        String result = null;
        if ("content".equals(uri.getScheme())) {
            try (android.database.Cursor cursor = getContentResolver().query(uri, null, null, null, null)) {
                if (cursor != null && cursor.moveToFirst()) {
                    int idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
                    if (idx >= 0) result = cursor.getString(idx);
                }
            } catch (Exception ignored) {
            }
        }
        if (result == null) result = uri.getLastPathSegment();
        return result;
    }
}
