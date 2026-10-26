package com.fongmi.android.tv.ui.music.view.theme;

import android.content.Context;

import com.fongmi.android.tv.ui.music.MusicPlayerSettings;

/** 主题 id → 主题视图（经典不在此列：经典是播放页本体版式）。 */
public final class ThemeViewFactory {

    private ThemeViewFactory() {
    }

    public static PlayerThemeView create(Context context, String themeId,
                                         PlayerThemeView.Host host) {
        if (themeId == null) return null;
        switch (themeId) {
            case MusicPlayerSettings.THEME_COVER_IMMERSIVE:
                return new CoverImmersiveThemeView(context, host);
            case MusicPlayerSettings.THEME_APPLE_MUSIC:
                return new AppleMusicThemeView(context, host);
            case MusicPlayerSettings.THEME_LUMINOUS_CD:
            case MusicPlayerSettings.THEME_LUMINOUS_CD_DARK:
            case MusicPlayerSettings.THEME_LUMINOUS_CD_AUTO:
                return new LuminousCdThemeView(context, host);
            case MusicPlayerSettings.THEME_RECORD_SHELF:
                return new RecordShelfThemeView(context, host);
            default:
                return null;
        }
    }
}
