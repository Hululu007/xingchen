package com.fongmi.android.tv.localbook;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.fongmi.android.tv.read.ReadingRouter;
import com.fongmi.android.tv.read.ReadingSession;

/**
 * 从文件管理器"用星辰打开" epub/zip/cbz 的分发页：
 * 导入成本地书 → 直接进阅读器 → finish。
 * <p>
 * txt 不走这里：HomeActivity 已按既有需求（2026-10-10）把 text/plain
 * 接成混合链接导入/直播列表，保持不动。
 */
public class LocalBookOpenActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Intent intent = getIntent();
        Uri uri = intent == null ? null : intent.getData();
        if (uri == null || !Intent.ACTION_VIEW.equals(intent.getAction())) {
            finish();
            return;
        }
        new Thread(() -> {
            LocalBook book = null;
            String err = "";
            try {
                LocalBookImporter.Result r = LocalBookImporter.importUri(getApplicationContext(), uri);
                book = r.book;
            } catch (Exception e) {
                err = e.getMessage();
            }
            final LocalBook fBook = book;
            final String fErr = err;
            runOnUiThread(() -> {
                if (fBook != null) {
                    ReadingSession s = LocalBookSession.build(fBook, 0);
                    if (s != null) {
                        if (s.kind == ReadingSession.Kind.COMIC) {
                            ReadingRouter.openReadingWithStartPage(this, s, 0);
                        } else {
                            ReadingRouter.openReading(this, s);
                        }
                    }
                } else {
                    Toast.makeText(this, "打不开这本书：" + fErr, Toast.LENGTH_LONG).show();
                }
                finish();
            });
        }).start();
    }
}
