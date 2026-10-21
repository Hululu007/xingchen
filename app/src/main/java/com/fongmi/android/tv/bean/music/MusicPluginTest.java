package com.fongmi.android.tv.bean.music;

/**
 * 插件检测结果（C4，照瓜子 LxPluginScreen 四态＋LxLyricTestResult 字段口径转写）。
 * state 四态：UNTESTED 未检测／AVAILABLE 检测可用／FAILED 检测失败／BLOCKED 已屏蔽
 * （已屏蔽与检测失败共用瓜子红 #FFFF3B30；可用 #FF34C759；未检测 #FF8E8E93）。
 * 内存表之外的持久化走 MusicStore.music_plugin_tests（JSON 存表）。
 */
public class MusicPluginTest {

    public enum State {
        UNTESTED,
        AVAILABLE,
        FAILED,
        BLOCKED
    }

    /** 四态 */
    public State state = State.UNTESTED;
    /** 上次检测时间戳 ms（对应瓜子 testedAt） */
    public long testedAt;
    /** 音源探针是否通过（搜索＋首条取链出链） */
    public boolean audioOk;
    /** 歌词探针是否通过 */
    public boolean lyricOk;
    /** 插件是否有歌词能力（无歌词能力时只看音源，见 MusicSourceManager.testPlugin） */
    public boolean lyricCapable = true;
    /** 失败／提示文案（对应瓜子歌词结果 message） */
    public String message = "";
    /** 歌词探针首行预览（对应瓜子歌词结果 preview，截断 30 字内） */
    public String preview = "";
    /** 音源取链耗时 ms（写入耗时表作初始值，不覆盖真实取链耗时） */
    public long latencyMs;

    public MusicPluginTest() {
    }

    /** 行内状态文案（瓜子四态文案） */
    public String label() {
        switch (state) {
            case AVAILABLE:
                return "检测可用";
            case FAILED:
                return "检测失败";
            case BLOCKED:
                return "已屏蔽";
            default:
                return "未检测";
        }
    }

    /** 行内状态文案色（瓜子四态色） */
    public int labelColor() {
        switch (state) {
            case AVAILABLE:
                return 0xFF34C759;
            case FAILED:
            case BLOCKED:
                return 0xFFFF3B30;
            default:
                return 0xFF8E8E93;
        }
    }
}
