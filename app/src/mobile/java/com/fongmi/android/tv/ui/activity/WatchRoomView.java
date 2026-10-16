package com.fongmi.android.tv.ui.activity;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.LinearInterpolator;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 一起看房间页（单独一套直播间式界面，照潇洒哥定的样子：上面播放、下面说话）。
 * 深色直播间底：顶部信息条（房号/房主/人数）+ 成员头像条 + 聊天流 + 底部输入栏；
 * 发言同时往视频上飘一条弹幕（FloatLayer 盖在播放画面上、不吃触摸）。
 * 播放仍走 VideoActivity 原播放器，本类只管房间内的看与说。
 */
public class WatchRoomView extends FrameLayout {

    public interface Action {
        void onSend(String text);

        void onToggleVoice();

        void onLeave();

        void onCollapse();
    }

    private static final int BG = Color.rgb(19, 21, 26);
    private static final int PANEL_LINE = Color.rgb(38, 42, 51);
    private static final int FIELD_BG = Color.rgb(29, 32, 39);
    private static final int BUBBLE_OTHER = Color.rgb(35, 38, 45);
    private static final int ACCENT = Color.rgb(245, 197, 24);
    private static final int TEXT_MAIN = Color.WHITE;
    private static final int TEXT_SUB = Color.rgb(201, 205, 212);
    private static final int TEXT_HINT = Color.rgb(148, 153, 163);

    private final Action action;
    private final TextView titleView;
    private final TextView subView;
    private final TextView voiceBtn;
    private final LinearLayout memberRow;
    private final View memberStrip;
    private final RecyclerView chatList;
    private final ChatAdapter adapter;
    private final EditText input;

    private String myName = "";

    public WatchRoomView(Context context, Action action) {
        super(context);
        this.action = action;
        setBackground(roundRect(BG, dp(18), true));
        setClipToOutline(true);

        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        addView(root, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        // ---- 顶部信息条 ----
        LinearLayout header = new LinearLayout(context);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(14), dp(10), dp(8), dp(8));
        root.addView(header, new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));

        LinearLayout titles = new LinearLayout(context);
        titles.setOrientation(LinearLayout.VERTICAL);
        header.addView(titles, new LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));
        titleView = text(context, "房间", 15, TEXT_MAIN, true);
        subView = text(context, "", 12, TEXT_SUB, false);
        titles.addView(titleView);
        titles.addView(subView);

        voiceBtn = pillButton(context, "合唱：关");
        voiceBtn.setOnClickListener(v -> {
            if (this.action != null) this.action.onToggleVoice();
        });
        header.addView(voiceBtn);
        TextView collapseBtn = pillButton(context, "收起");
        collapseBtn.setOnClickListener(v -> {
            if (this.action != null) this.action.onCollapse();
        });
        header.addView(collapseBtn);
        TextView leaveBtn = pillButton(context, "离开");
        leaveBtn.setTextColor(Color.rgb(255, 122, 122));
        leaveBtn.setOnClickListener(v -> {
            if (this.action != null) this.action.onLeave();
        });
        header.addView(leaveBtn);

        // ---- 成员头像条 ----
        HorizontalScrollView memberScroll = new HorizontalScrollView(context);
        memberStrip = memberScroll;
        memberScroll.setHorizontalScrollBarEnabled(false);
        memberRow = new LinearLayout(context);
        memberRow.setOrientation(LinearLayout.HORIZONTAL);
        memberRow.setPadding(dp(14), dp(2), dp(14), dp(8));
        memberScroll.addView(memberRow);
        root.addView(memberScroll, new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));

        View line = new View(context);
        line.setBackgroundColor(PANEL_LINE);
        root.addView(line, new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, Math.max(1, dp(1))));

        // ---- 聊天流 ----
        chatList = new RecyclerView(context);
        chatList.setPadding(dp(12), dp(8), dp(12), dp(8));
        chatList.setClipToPadding(false);
        LinearLayoutManager lm = new LinearLayoutManager(context);
        lm.setStackFromEnd(true);
        chatList.setLayoutManager(lm);
        adapter = new ChatAdapter(context);
        chatList.setAdapter(adapter);
        root.addView(chatList, new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f));

        // ---- 底部输入栏 ----
        LinearLayout inputRow = new LinearLayout(context);
        inputRow.setOrientation(LinearLayout.HORIZONTAL);
        inputRow.setGravity(Gravity.CENTER_VERTICAL);
        inputRow.setPadding(dp(12), dp(8), dp(12), dp(12));
        root.addView(inputRow, new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));

        input = new EditText(context);
        input.setHint("说句话，大家都能看见");
        input.setHintTextColor(TEXT_HINT);
        input.setTextColor(TEXT_MAIN);
        input.setTextSize(14);
        input.setSingleLine(true);
        input.setPadding(dp(14), dp(9), dp(14), dp(9));
        input.setBackground(roundRect(FIELD_BG, dp(20), false));
        inputRow.addView(input, new LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));

        TextView sendBtn = new TextView(context);
        sendBtn.setText("发送");
        sendBtn.setTextSize(14);
        sendBtn.setTextColor(Color.rgb(26, 26, 26));
        sendBtn.getPaint().setFakeBoldText(true);
        sendBtn.setGravity(Gravity.CENTER);
        sendBtn.setPadding(dp(18), dp(9), dp(18), dp(9));
        GradientDrawable sd = new GradientDrawable();
        sd.setColor(ACCENT);
        sd.setCornerRadius(dp(20));
        sendBtn.setBackground(sd);
        LinearLayout.LayoutParams sendLp = new LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        sendLp.leftMargin = dp(8);
        inputRow.addView(sendBtn, sendLp);
        sendBtn.setOnClickListener(v -> sendCurrent());
        input.setOnEditorActionListener((v, actionId, event) -> {
            sendCurrent();
            return true;
        });
    }

    private void sendCurrent() {
        String text = input.getText() == null ? "" : input.getText().toString().trim();
        if (text.isEmpty()) return;
        input.setText("");
        if (action != null) action.onSend(text);
    }

    /** 横屏：全屏看片、别人发话跟弹幕一样飘——房间只留底部一条小条（信息+输入），不摆聊天墙。 */
    public void setLandscapeMode(boolean on) {
        setBackground(roundRect(on ? android.graphics.Color.argb(228, 19, 21, 26) : BG, dp(18), true));
        chatList.setVisibility(on ? View.GONE : View.VISIBLE);
        memberStrip.setVisibility(on ? View.GONE : View.VISIBLE);
    }

    public void setMyName(String name) {
        myName = name == null ? "" : name;
    }

    /** 点标题区（复制房号用）。 */
    public void setOnTitleClick(Runnable r) {
        View.OnClickListener l = v -> {
            if (r != null) r.run();
        };
        titleView.setOnClickListener(l);
        subView.setOnClickListener(l);
    }

    public void setRoomInfo(String roomCode, boolean isHost, int memberCount) {
        titleView.setText("房间 " + roomCode);
        subView.setText((isHost ? "你是房主 · " : "") + memberCount + " 人在看" + (isHost ? " · 点房号可复制发给朋友" : ""));
    }

    public void setVoiceState(boolean on) {
        voiceBtn.setText(on ? "合唱：开" : "合唱：关");
        voiceBtn.setTextColor(on ? Color.rgb(26, 26, 26) : TEXT_MAIN);
        GradientDrawable d = new GradientDrawable();
        d.setColor(on ? ACCENT : FIELD_BG);
        d.setCornerRadius(dp(16));
        voiceBtn.setBackground(d);
    }

    public void updateMembers(List<Map<String, Object>> members) {
        memberRow.removeAllViews();
        Context context = getContext();
        for (Map<String, Object> m : members) {
            String name = String.valueOf(m.get("name"));
            boolean host = Boolean.TRUE.equals(m.get("host"));
            LinearLayout item = new LinearLayout(context);
            item.setOrientation(LinearLayout.VERTICAL);
            item.setGravity(Gravity.CENTER_HORIZONTAL);
            LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(dp(52), LayoutParams.WRAP_CONTENT);
            item.setLayoutParams(ilp);
            TextView avatar = new TextView(context);
            avatar.setText(name.isEmpty() ? "友" : name.substring(0, 1));
            avatar.setTextColor(Color.WHITE);
            avatar.setTextSize(16);
            avatar.getPaint().setFakeBoldText(true);
            avatar.setGravity(Gravity.CENTER);
            GradientDrawable ad = new GradientDrawable();
            ad.setShape(GradientDrawable.OVAL);
            ad.setColor(colorOf(name));
            if (host) ad.setStroke(dp(2), ACCENT);
            avatar.setBackground(ad);
            item.addView(avatar, new LinearLayout.LayoutParams(dp(38), dp(38)));
            TextView label = text(context, host ? name + "·房主" : name, 10, TEXT_SUB, false);
            label.setGravity(Gravity.CENTER);
            label.setMaxLines(1);
            label.setEllipsize(TextUtils.TruncateAt.END);
            item.addView(label, new LinearLayout.LayoutParams(dp(52), LayoutParams.WRAP_CONTENT));
            memberRow.addView(item);
        }
    }

    public void addChat(String name, String text) {
        boolean self = name != null && !name.isEmpty() && name.equals(myName);
        adapter.add(new Msg(Msg.CHAT, name == null || name.isEmpty() ? "伙伴" : name, text, self));
        chatList.scrollToPosition(adapter.getItemCount() - 1);
    }

    public void addSystem(String text) {
        if (text == null || text.isEmpty()) return;
        adapter.add(new Msg(Msg.SYSTEM, "", text, false));
        chatList.scrollToPosition(adapter.getItemCount() - 1);
    }

    // ---------------- 弹幕飘层 ----------------

    /** 盖在播放画面上的飘字层：不吃触摸，聊天来了从右往左飘。 */
    public static class FloatLayer extends FrameLayout {
        private int lane = 0;

        public FloatLayer(Context context) {
            super(context);
            setClickable(false);
            setFocusable(false);
        }

        @Override
        public boolean onTouchEvent(android.view.MotionEvent event) {
            return false;
        }

        public void floatText(String name, String text) {
            Context context = getContext();
            TextView tv = new TextView(context);
            tv.setText((name == null || name.isEmpty() ? "" : name + "：") + text);
            tv.setTextColor(Color.WHITE);
            tv.setTextSize(15);
            tv.getPaint().setFakeBoldText(true);
            tv.setShadowLayer(4, 0, 1, Color.argb(200, 0, 0, 0));
            tv.setSingleLine(true);
            int w = getWidth();
            int h = getHeight();
            if (w <= 0 || h <= 0) return;
            int lanes = 4;
            int laneH = Math.max(24, h / 6);
            int y = (lane++ % lanes) * laneH + 6;
            LayoutParams lp = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
            lp.topMargin = Math.min(y, Math.max(0, h - laneH));
            addView(tv, lp);
            tv.measure(MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
            int tw = tv.getMeasuredWidth();
            tv.setTranslationX(w);
            tv.animate().translationX(-tw).setDuration(6500 + tw).setInterpolator(new LinearInterpolator())
                    .withEndAction(() -> removeView(tv)).start();
        }
    }

    // ---------------- 聊天列表 ----------------

    private static class Msg {
        static final int CHAT = 0;
        static final int SYSTEM = 1;
        final int type;
        final String name;
        final String text;
        final boolean self;

        Msg(int type, String name, String text, boolean self) {
            this.type = type;
            this.name = name;
            this.text = text;
            this.self = self;
        }
    }

    private class ChatAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        private final Context context;
        private final List<Msg> items = new ArrayList<>();

        ChatAdapter(Context context) {
            this.context = context;
        }

        void add(Msg m) {
            items.add(m);
            while (items.size() > 200) items.remove(0);
            notifyDataSetChanged();
        }

        @Override
        public int getItemViewType(int position) {
            return items.get(position).type;
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            if (viewType == Msg.SYSTEM) {
                TextView tv = text(context, "", 11, TEXT_SUB, false);
                tv.setGravity(Gravity.CENTER);
                tv.setPadding(dp(10), dp(3), dp(10), dp(3));
                tv.setBackground(roundRect(FIELD_BG, dp(10), false));
                LinearLayout wrap = new LinearLayout(context);
                wrap.setGravity(Gravity.CENTER);
                wrap.setPadding(0, dp(4), 0, dp(4));
                wrap.addView(tv);
                wrap.setLayoutParams(new RecyclerView.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
                return new RecyclerView.ViewHolder(wrap) {
                };
            }
            LinearLayout row = new LinearLayout(context);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setPadding(0, dp(4), 0, dp(4));
            row.setLayoutParams(new RecyclerView.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
            TextView avatar = new TextView(context);
            avatar.setGravity(Gravity.CENTER);
            avatar.setTextColor(Color.WHITE);
            avatar.setTextSize(13);
            avatar.getPaint().setFakeBoldText(true);
            LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(dp(30), dp(30));
            row.addView(avatar, alp);
            LinearLayout col = new LinearLayout(context);
            col.setOrientation(LinearLayout.VERTICAL);
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f);
            clp.leftMargin = dp(8);
            clp.rightMargin = dp(8);
            row.addView(col, clp);
            TextView name = text(context, "", 11, TEXT_SUB, false);
            col.addView(name);
            TextView bubble = text(context, "", 14, TEXT_MAIN, false);
            bubble.setPadding(dp(11), dp(7), dp(11), dp(7));
            bubble.setLineSpacing(0, 1.15f);
            col.addView(bubble);
            return new RecyclerView.ViewHolder(row) {
            };
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            Msg m = items.get(position);
            if (m.type == Msg.SYSTEM) {
                ((TextView) ((LinearLayout) holder.itemView).getChildAt(0)).setText(m.text);
                return;
            }
            LinearLayout row = (LinearLayout) holder.itemView;
            TextView avatar = (TextView) row.getChildAt(0);
            LinearLayout col = (LinearLayout) row.getChildAt(1);
            TextView name = (TextView) col.getChildAt(0);
            TextView bubble = (TextView) col.getChildAt(1);
            row.setGravity(m.self ? Gravity.END : Gravity.START);
            avatar.setVisibility(m.self ? View.GONE : View.VISIBLE);
            avatar.setText(m.name.isEmpty() ? "友" : m.name.substring(0, 1));
            GradientDrawable ad = new GradientDrawable();
            ad.setShape(GradientDrawable.OVAL);
            ad.setColor(colorOf(m.name));
            avatar.setBackground(ad);
            name.setText(m.self ? "" : m.name);
            name.setVisibility(m.self ? View.GONE : View.VISIBLE);
            bubble.setText(m.text);
            bubble.setTextColor(m.self ? Color.rgb(26, 26, 26) : TEXT_MAIN);
            GradientDrawable bd = new GradientDrawable();
            bd.setColor(m.self ? ACCENT : BUBBLE_OTHER);
            bd.setCornerRadius(dp(12));
            bubble.setBackground(bd);
        }

        @Override
        public int getItemCount() {
            return items.size();
        }
    }

    // ---------------- 小工具 ----------------

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private static GradientDrawable roundRect(int color, int radius, boolean topOnly) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        if (topOnly) d.setCornerRadii(new float[]{radius, radius, radius, radius, 0, 0, 0, 0});
        else d.setCornerRadius(radius);
        return d;
    }

    private TextView pillButton(Context context, String label) {
        TextView tv = text(context, label, 12, TEXT_MAIN, false);
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(dp(11), dp(6), dp(11), dp(6));
        tv.setBackground(roundRect(FIELD_BG, dp(16), false));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        lp.leftMargin = dp(6);
        tv.setLayoutParams(lp);
        return tv;
    }

    private static TextView text(Context context, String s, int sp, int color, boolean bold) {
        TextView tv = new TextView(context);
        tv.setText(s);
        tv.setTextSize(sp);
        tv.setTextColor(color);
        if (bold) tv.getPaint().setFakeBoldText(true);
        return tv;
    }

    private static int colorOf(String name) {
        int[] palette = {
                Color.rgb(233, 113, 95), Color.rgb(84, 160, 233), Color.rgb(94, 190, 130),
                Color.rgb(176, 124, 224), Color.rgb(240, 168, 64), Color.rgb(84, 197, 204),
                Color.rgb(224, 110, 168), Color.rgb(140, 160, 90)
        };
        int h = 0;
        if (name != null) for (int i = 0; i < name.length(); i++) h = h * 31 + name.charAt(i);
        return palette[Math.abs(h) % palette.length];
    }
}
