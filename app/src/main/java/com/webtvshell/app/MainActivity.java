package com.webtvshell.app;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.GestureDetector;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebView;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.webtvshell.app.browser.BrowserController;
import com.webtvshell.app.config.ConfigRepository;
import com.webtvshell.app.model.AppConfig;
import com.webtvshell.app.model.Channel;
import com.webtvshell.app.model.ChannelGroup;
import com.webtvshell.app.model.ChannelSource;
import com.webtvshell.app.server.LanConfigServer;

import org.json.JSONException;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class MainActivity extends Activity implements BrowserController.Listener {
    private static final long KEY_THROTTLE_MS = 180L;
    private static final long GESTURE_THROTTLE_MS = 350L;

    private BrowserController browser;
    private ConfigRepository repository;
    private LanConfigServer configServer;
    private AppConfig config;
    private final List<ChannelGroup> visibleGroups = new ArrayList<>();
    private List<Channel> visibleChannels = Collections.emptyList();
    private List<Channel> menuChannels = Collections.emptyList();

    private View channelPanel;
    private View emptyView;
    private HorizontalScrollView groupScroll;
    private LinearLayout groupList;
    private LinearLayout channelList;
    private ScrollView channelScroll;
    private TextView configAddress;
    private TextView panelConfigAddress;

    private int groupIndex;
    private int channelIndex;
    private int menuGroupIndex;
    private int menuChannelIndex;
    private boolean menuOpen;
    private long lastKeyAt;
    private long lastLoadFailureAt;
    private long lastGestureAt;
    private GestureDetector playbackGestures;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        bindViews();
        setupPlaybackGestures();
        enterImmersiveMode();

        browser = new BrowserController(
                (WebView) findViewById(R.id.web_view),
                (FrameLayout) findViewById(R.id.custom_view_container), this);

        try {
            repository = new ConfigRepository(this);
            startConfigServer();
            loadActiveConfig();
        } catch (IOException | JSONException error) {
            showEmptyState("配置读取失败: " + error.getMessage());
        }
    }

    private void bindViews() {
        channelPanel = findViewById(R.id.channel_panel);
        emptyView = findViewById(R.id.empty_view);
        groupScroll = findViewById(R.id.group_scroll);
        groupList = findViewById(R.id.group_list_container);
        channelList = findViewById(R.id.channel_list_container);
        channelScroll = findViewById(R.id.channel_scroll);
        configAddress = findViewById(R.id.config_address);
        panelConfigAddress = findViewById(R.id.panel_config_address);
    }

    private void setupPlaybackGestures() {
        playbackGestures = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDown(MotionEvent event) {
                return true;
            }

            @Override
            public boolean onSingleTapConfirmed(MotionEvent event) {
                if (!canHandlePlaybackGesture()) return false;
                openMenu();
                return true;
            }

            @Override
            public boolean onFling(MotionEvent start, MotionEvent end,
                                   float velocityX, float velocityY) {
                if (!canHandlePlaybackGesture() || start == null || end == null) return false;
                float deltaX = end.getX() - start.getX();
                float deltaY = end.getY() - start.getY();
                float minDistance = dp(80);
                float minVelocity = dp(450);
                long now = SystemClock.elapsedRealtime();
                if (now - lastGestureAt < GESTURE_THROTTLE_MS) return false;

                if (Math.abs(deltaY) > Math.abs(deltaX) * 1.2f
                        && Math.abs(deltaY) >= minDistance
                        && Math.abs(velocityY) >= minVelocity) {
                    lastGestureAt = now;
                    changePlayingChannel(deltaY < 0 ? 1 : -1);
                    return true;
                }
                if (Math.abs(deltaX) > Math.abs(deltaY) * 1.2f
                        && Math.abs(deltaX) >= minDistance
                        && Math.abs(velocityX) >= minVelocity) {
                    lastGestureAt = now;
                    changePlayingGroup(deltaX < 0 ? 1 : -1);
                    return true;
                }
                return false;
            }
        });
    }

    private boolean canHandlePlaybackGesture() {
        return !menuOpen && emptyView.getVisibility() != View.VISIBLE && !visibleChannels.isEmpty();
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        if (playbackGestures != null && canHandlePlaybackGesture()) {
            playbackGestures.onTouchEvent(event);
        }
        // Observe playback gestures without consuming them so website controls keep working.
        return super.dispatchTouchEvent(event);
    }

    private void startConfigServer() {
        configServer = new LanConfigServer(this, repository,
                () -> runOnUiThread(this::restartApp));
        try {
            configServer.startServer();
            setConfigAddress(configServer.getAddress());
        } catch (IllegalStateException error) {
            configServer = null;
            setConfigAddress("配置服务未启动");
            Toast.makeText(this, error.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void setConfigAddress(String address) {
        configAddress.setText(address);
        panelConfigAddress.setText(address);
    }

    private void restartApp() {
        if (configServer != null) {
            configServer.stopServer();
            configServer = null;
        }
        recreate();
    }

    private void loadActiveConfig() throws IOException, JSONException {
        config = AppConfig.parse(repository.getActiveJson());
        visibleGroups.clear();
        for (ChannelGroup group : config.groups) {
            if (!config.channelsForGroup(group.id).isEmpty()) visibleGroups.add(group);
        }
        if (visibleGroups.isEmpty()) {
            showEmptyState(null);
            return;
        }
        emptyView.setVisibility(View.GONE);
        groupIndex = clamp(groupIndex, visibleGroups.size());
        selectGroup(groupIndex, false);
        menuGroupIndex = groupIndex;
        menuChannels = visibleChannels;
        playCurrentChannel();
    }

    private void showEmptyState(String error) {
        emptyView.setVisibility(View.VISIBLE);
        channelPanel.setVisibility(View.GONE);
        menuOpen = false;
        if (configServer != null) {
            setConfigAddress(configServer.getAddress());
        } else if (configAddress.length() == 0) {
            setConfigAddress("配置服务未启动");
        }
        if (error != null) Toast.makeText(this, error, Toast.LENGTH_LONG).show();
    }

    private void selectGroup(int newIndex, boolean startPlayback) {
        if (visibleGroups.isEmpty()) return;
        groupIndex = wrap(newIndex, visibleGroups.size());
        visibleChannels = config.channelsForGroup(visibleGroups.get(groupIndex).id);
        channelIndex = clamp(channelIndex, visibleChannels.size());
        if (menuOpen) {
            menuGroupIndex = groupIndex;
            menuChannels = visibleChannels;
            menuChannelIndex = channelIndex;
            renderChannelMenu();
        }
        if (startPlayback) playCurrentChannel();
    }

    private void playCurrentChannel() {
        if (visibleChannels.isEmpty()) return;
        channelIndex = clamp(channelIndex, visibleChannels.size());
        Channel channel = visibleChannels.get(channelIndex);
        ChannelSource source = channel.currentSource();
        if (source != null) browser.load(config, source);
        if (menuOpen) renderChannelMenu();
    }

    private void changePlayingChannel(int direction) {
        if (visibleChannels.isEmpty()) return;
        channelIndex = wrap(channelIndex + direction, visibleChannels.size());
        menuChannelIndex = channelIndex;
        playCurrentChannel();
    }

    private void changePlayingGroup(int direction) {
        if (visibleGroups.isEmpty()) return;
        groupIndex = wrap(groupIndex + direction, visibleGroups.size());
        visibleChannels = config.channelsForGroup(visibleGroups.get(groupIndex).id);
        channelIndex = 0;
        menuChannelIndex = 0;
        playCurrentChannel();
    }

    private void openMenu() {
        if (visibleGroups.isEmpty()) return;
        if (browser.isFullscreen()) browser.exitFullscreen();
        menuOpen = true;
        menuGroupIndex = groupIndex;
        menuChannels = visibleChannels;
        menuChannelIndex = channelIndex;
        channelPanel.setVisibility(View.VISIBLE);
        renderChannelMenu();
    }

    private void closeMenu() {
        menuOpen = false;
        channelPanel.setVisibility(View.GONE);
        if (browser != null) browser.enterCinemaMode();
        enterImmersiveMode();
    }

    private void renderChannelMenu() {
        if (!menuOpen || visibleGroups.isEmpty()) return;
        renderGroupTabs();
        channelList.removeAllViews();
        if (menuChannels.isEmpty()) return;
        menuChannelIndex = clamp(menuChannelIndex, menuChannels.size());

        for (int i = 0; i < menuChannels.size(); i++) {
            final int itemIndex = i;
            Channel channel = menuChannels.get(i);
            TextView item = new TextView(this);
            item.setText(channel.name);
            item.setTextColor(Color.WHITE);
            item.setTextSize(19f);
            item.setGravity(Gravity.CENTER_VERTICAL);
            item.setSingleLine(true);
            item.setEllipsize(android.text.TextUtils.TruncateAt.END);
            item.setFocusable(true);
            item.setSelected(menuGroupIndex == groupIndex && i == channelIndex);
            item.setBackgroundResource(R.drawable.bg_channel_item);
            item.setPadding(dp(16), 0, dp(12), 0);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(52));
            params.bottomMargin = dp(4);
            channelList.addView(item, params);
            item.setOnClickListener(view -> {
                menuChannelIndex = itemIndex;
                confirmMenuSelection();
            });
        }
        channelList.post(() -> focusMenuItem(menuChannelIndex));
    }

    private void renderGroupTabs() {
        groupList.removeAllViews();
        for (int i = 0; i < visibleGroups.size(); i++) {
            final int itemIndex = i;
            TextView item = new TextView(this);
            item.setText(visibleGroups.get(i).name);
            item.setTextColor(Color.WHITE);
            item.setTextSize(15f);
            item.setGravity(Gravity.CENTER);
            item.setSingleLine(true);
            item.setMinWidth(dp(92));
            item.setPadding(dp(12), 0, dp(12), 0);
            item.setSelected(i == menuGroupIndex);
            item.setBackgroundResource(R.drawable.bg_group_item);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, dp(40));
            params.rightMargin = dp(4);
            groupList.addView(item, params);
            item.setOnClickListener(view -> browseMenuGroup(itemIndex));
            item.setFocusable(false);
            item.setFocusableInTouchMode(false);
        }
        groupList.post(() -> {
            if (menuGroupIndex < 0 || menuGroupIndex >= groupList.getChildCount()) return;
            View item = groupList.getChildAt(menuGroupIndex);
            groupScroll.smoothScrollTo(Math.max(0, item.getLeft() - dp(12)), 0);
        });
    }

    private void focusMenuItem(int index) {
        if (!menuOpen || index < 0 || index >= channelList.getChildCount()) return;
        View item = channelList.getChildAt(index);
        item.requestFocus();
        channelScroll.smoothScrollTo(0, Math.max(0, item.getTop() - dp(80)));
    }

    private void moveMenuSelection(int direction) {
        if (menuChannels.isEmpty()) return;
        menuChannelIndex = wrap(menuChannelIndex + direction, menuChannels.size());
        focusMenuItem(menuChannelIndex);
    }

    private void changeMenuGroup(int direction) {
        browseMenuGroup(menuGroupIndex + direction);
    }

    private void browseMenuGroup(int newIndex) {
        if (visibleGroups.isEmpty()) return;
        menuGroupIndex = wrap(newIndex, visibleGroups.size());
        menuChannels = config.channelsForGroup(visibleGroups.get(menuGroupIndex).id);
        menuChannelIndex = menuGroupIndex == groupIndex
                ? clamp(channelIndex, menuChannels.size()) : 0;
        renderChannelMenu();
    }

    private void confirmMenuSelection() {
        if (menuChannels.isEmpty()) return;
        groupIndex = menuGroupIndex;
        visibleChannels = menuChannels;
        channelIndex = menuChannelIndex;
        closeMenu();
        playCurrentChannel();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        int keyCode = event.getKeyCode();
        if (!isRemoteNavigationKey(keyCode)) return super.dispatchKeyEvent(event);
        if (event.getAction() == KeyEvent.ACTION_UP) return true;
        if (event.getAction() != KeyEvent.ACTION_DOWN || event.getRepeatCount() > 0) return true;

        long now = SystemClock.elapsedRealtime();
        if (now - lastKeyAt < KEY_THROTTLE_MS) return true;
        lastKeyAt = now;

        if (keyCode == KeyEvent.KEYCODE_MENU) {
            if (menuOpen) closeMenu(); else openMenu();
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            handleBackPressed();
            return true;
        }

        if (menuOpen) {
            if (keyCode == KeyEvent.KEYCODE_DPAD_UP) moveMenuSelection(-1);
            else if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) moveMenuSelection(1);
            else if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) changeMenuGroup(-1);
            else if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) changeMenuGroup(1);
            else if (isConfirmKey(keyCode)) confirmMenuSelection();
            return true;
        }

        if (keyCode == KeyEvent.KEYCODE_DPAD_UP) changePlayingChannel(-1);
        else if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) changePlayingChannel(1);
        else if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) changePlayingGroup(-1);
        else if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) changePlayingGroup(1);
        else if (isConfirmKey(keyCode)) browser.handleConfirmGesture();
        return true;
    }

    private void handleBackPressed() {
        if (menuOpen) {
            closeMenu();
        } else if (browser != null && browser.isFullscreen()) {
            browser.exitFullscreen();
        } else {
            finish();
        }
    }

    private static boolean isRemoteNavigationKey(int keyCode) {
        return keyCode == KeyEvent.KEYCODE_MENU
                || keyCode == KeyEvent.KEYCODE_BACK
                || keyCode == KeyEvent.KEYCODE_DPAD_UP
                || keyCode == KeyEvent.KEYCODE_DPAD_DOWN
                || keyCode == KeyEvent.KEYCODE_DPAD_LEFT
                || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT
                || isConfirmKey(keyCode);
    }

    private static boolean isConfirmKey(int keyCode) {
        return keyCode == KeyEvent.KEYCODE_DPAD_CENTER
                || keyCode == KeyEvent.KEYCODE_ENTER
                || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER;
    }

    @Override
    public void onFullscreenChanged(boolean fullscreen) {
        enterImmersiveMode();
    }

    @Override
    public void onMainFrameLoadFailed() {
        long now = SystemClock.elapsedRealtime();
        if (now - lastLoadFailureAt < 2000 || visibleChannels.isEmpty()) return;
        lastLoadFailureAt = now;
        Channel channel = visibleChannels.get(clamp(channelIndex, visibleChannels.size()));
        if (channel.sources.size() > 1) browser.load(config, channel.nextSource());
    }

    private void enterImmersiveMode() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (browser != null) browser.resume();
        enterImmersiveMode();
    }

    @Override
    protected void onPause() {
        if (browser != null) browser.pause();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        if (configServer != null) configServer.stopServer();
        if (browser != null) browser.destroy();
        super.onDestroy();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private static int wrap(int value, int size) {
        if (size <= 0) return 0;
        int wrapped = value % size;
        return wrapped < 0 ? wrapped + size : wrapped;
    }

    private static int clamp(int value, int size) {
        if (size <= 0) return 0;
        return Math.max(0, Math.min(value, size - 1));
    }
}
