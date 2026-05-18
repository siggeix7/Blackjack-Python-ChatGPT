package com.siggeix7.blackjack;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;

public final class MainActivity extends Activity {
    private static final String PREFS = "blackjack_royal";
    private static final int FELT_DARK = 0xFF050811;
    private static final int PANEL = 0xEA0B101B;
    private static final int GOLD = 0xFFFFC85A;
    private static final int GOLD_DARK = 0xFFE1912F;
    private static final int BLUE = 0xFF2563EB;
    private static final int GREEN = 0xFF14B878;
    private static final int RED = 0xFFE5485E;
    private static final int TEXT = 0xFFF8FBFF;
    private static final int MUTED = 0xFFB8C5D6;
    private static final int CYAN = 0xFF32D5FF;
    private static final int VIOLET = 0xFF9C5CFF;

    private SharedPreferences prefs;
    private BlackjackGame game;
    private Handler handler;
    private ToneGenerator tone;
    private FrameLayout root;
    private FrameLayout tableLayer;
    private LinearLayout actionDock;
    private TextView logTicker;
    private String currentProfile = "Giocatore";
    private boolean soundEnabled = true;
    private boolean vibrationEnabled = true;
    private boolean fastAnimations;

    private final HashMap<String, Integer> lastCardCounts = new HashMap<String, Integer>();
    private final HashMap<String, Boolean> lastHiddenRows = new HashMap<String, Boolean>();
    private boolean firstRender = true;
    private boolean animateNextRender = true;
    private boolean animateCurrentRender;
    private boolean tableBusy;
    private boolean forceHideDealerHole = true;
    private String cinematicMessage = "Scegli la puntata e siediti al tavolo.";
    private int dealStep;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestLandscape();
        Window window = getWindow();
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        enterFullscreen();

        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        handler = new Handler(Looper.getMainLooper());
        tone = new ToneGenerator(AudioManager.STREAM_MUSIC, 45);
        loadAppSettings();
        String savedName = prefs.getString("name", null);
        currentProfile = prefs.getString("active_profile", savedName == null ? "Giocatore" : savedName);
        game = new BlackjackGame(currentProfile);
        if (savedName != null || prefs.contains(prefKey("balance"))) {
            restoreFromPrefs();
        }
        buildLayout();
        render();
        if (savedName == null) {
            showNameDialog();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (game != null && (game.phase == BlackjackGame.Phase.BETTING
                || game.phase == BlackjackGame.Phase.ROUND_OVER
                || game.phase == BlackjackGame.Phase.GAME_OVER)) {
            persistProgress();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (handler != null) {
            handler.removeCallbacksAndMessages(null);
        }
        if (tone != null) {
            tone.release();
            tone = null;
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            enterFullscreen();
        }
    }

    private void enterFullscreen() {
        applyFullscreen(getWindow());
    }

    private void requestLandscape() {
        try {
            setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
        } catch (RuntimeException ignored) {
            // Some Android releases can reject orientation requests; the game still runs portrait-safe.
        }
    }

    @SuppressWarnings("deprecation")
    private void applyFullscreen(Window window) {
        if (window == null) {
            return;
        }
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                window.setDecorFitsSystemWindows(false);
                WindowInsetsController controller = window.getInsetsController();
                if (controller != null) {
                    controller.hide(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
                    controller.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                }
            } else {
                applyLegacyFullscreen(window);
            }
        } catch (RuntimeException ignored) {
            applyLegacyFullscreen(window);
        }
    }

    @SuppressWarnings("deprecation")
    private void applyLegacyFullscreen(Window window) {
        window.getDecorView().setSystemUiVisibility(
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                | View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
    }

    private void buildLayout() {
        root = new FrameLayout(this);
        root.setBackgroundColor(FELT_DARK);
        root.addView(new RoomBackgroundView(this), new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT));

        tableLayer = new FrameLayout(this);
        tableLayer.setClipChildren(false);
        tableLayer.setClipToPadding(false);
        root.addView(tableLayer, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT));

        actionDock = new LinearLayout(this);
        actionDock.setOrientation(LinearLayout.VERTICAL);
        actionDock.setGravity(Gravity.CENTER);
        actionDock.setPadding(dp(12), dp(9), dp(12), dp(9));
        actionDock.setBackground(gradientRect(PANEL, 0xF10E1726, 0x5532D5FF, dp(22), dp(1)));
        actionDock.setElevation(dp(16));
        root.addView(actionDock, dockParams());

        logTicker = new TextView(this);
        logTicker.setTextColor(MUTED);
        logTicker.setTextSize(compact() ? 10 : 12);
        logTicker.setSingleLine(true);
        logTicker.setEllipsize(TextUtils.TruncateAt.START);
        logTicker.setGravity(Gravity.CENTER);
        logTicker.setPadding(dp(12), dp(5), dp(12), dp(5));
        logTicker.setBackground(roundRect(0x7B050A12, 0x334DB6FF, dp(14), dp(1)));

        setContentView(root);
    }

    private FrameLayout.LayoutParams dockParams() {
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(dockWidth(), ViewGroup.LayoutParams.WRAP_CONTENT);
        params.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        params.setMargins(0, 0, 0, dp(compact() ? 8 : 14));
        return params;
    }

    private void render() {
        animateCurrentRender = firstRender || animateNextRender;
        renderTable();
        renderActions();
        if (animateCurrentRender) {
            animateDock();
        }
        animateCurrentRender = false;
        animateNextRender = false;
        firstRender = false;
    }

    private void renderTable() {
        tableLayer.removeAllViews();
        tableLayer.addView(new CasinoTableView(this), new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT));

        addTitle();
        addTopHud();
        addMiniMenu();
        addDealerSeat();
        addCpuSeats();
        addHumanSeat();
        addMessageBubble();
    }

    private void addTitle() {
        LinearLayout brand = new LinearLayout(this);
        brand.setOrientation(LinearLayout.VERTICAL);
        brand.setPadding(dp(14), dp(9), dp(14), dp(10));
        brand.setBackground(gradientRect(0xD80A1220, 0xA80F2334, 0x6632D5FF, dp(18), dp(1)));
        brand.setElevation(dp(10));

        TextView overline = new TextView(this);
        overline.setText("LIVE TABLE");
        overline.setTextColor(CYAN);
        overline.setTextSize(compact() ? 8 : 10);
        overline.setTypeface(Typeface.DEFAULT_BOLD);
        overline.setLetterSpacing(0.22f);
        overline.setGravity(Gravity.LEFT);
        brand.addView(overline, fullWidth(0, 0, 0, 0));

        TextView title = new TextView(this);
        title.setText("BLACKJACK");
        title.setTextColor(TEXT);
        title.setTextSize(compact() ? 22 : 34);
        title.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD));
        title.setLetterSpacing(0.08f);
        title.setShadowLayer(dp(8), 0, dp(3), 0xCC000000);
        title.setGravity(Gravity.LEFT);
        brand.addView(title, fullWidth(0, 0, 0, 0));

        TextView subtitle = new TextView(this);
        subtitle.setText("Royal neon room");
        subtitle.setTextColor(MUTED);
        subtitle.setTextSize(compact() ? 9 : 11);
        subtitle.setGravity(Gravity.LEFT);
        brand.addView(subtitle, fullWidth(0, 0, 0, 0));

        tableLayer.addView(brand, anchoredParams(dp(compact() ? 190 : 292), ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.TOP | Gravity.LEFT, dp(compact() ? 8 : 18), dp(compact() ? 8 : 16), 0, 0));
    }

    private void addTopHud() {
        LinearLayout hud = new LinearLayout(this);
        hud.setOrientation(LinearLayout.HORIZONTAL);
        hud.setGravity(Gravity.CENTER);
        hud.setPadding(dp(5), dp(5), dp(5), dp(5));
        hud.setBackground(roundRect(0x8A050A12, 0x3332D5FF, dp(20), dp(1)));
        hud.setElevation(dp(8));
        hud.addView(statusChip("Saldo", BlackjackGame.money(game.human.balance)), weighted(1f, dp(3), 0, dp(3), 0));
        hud.addView(statusChip("Puntata", BlackjackGame.money(game.currentBet)), weighted(1f, dp(3), 0, dp(3), 0));
        hud.addView(statusChip("Tavolo", game.tableName()), weighted(1f, dp(3), 0, dp(3), 0));
        hud.addView(statusChip("Banco", BlackjackGame.money(game.dealerBankroll)), weighted(1f, dp(3), 0, dp(3), 0));
        tableLayer.addView(hud, anchoredParams(dp(compact() ? 410 : 620), ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.TOP | Gravity.RIGHT, 0, dp(compact() ? 8 : 16), dp(compact() ? 8 : 18), 0));
    }

    private TextView statusChip(String label, String value) {
        TextView chip = new TextView(this);
        chip.setText(label + "\n" + value);
        chip.setTextColor(TEXT);
        chip.setTextSize(compact() ? 10 : 12);
        chip.setTypeface(Typeface.DEFAULT_BOLD);
        chip.setGravity(Gravity.CENTER);
        chip.setPadding(dp(9), dp(7), dp(9), dp(7));
        chip.setBackground(gradientRect(0xE60E1726, 0xD80C2530, 0x5532D5FF, dp(15), dp(1)));
        return chip;
    }

    private void addMiniMenu() {
        LinearLayout menu = new LinearLayout(this);
        menu.setOrientation(LinearLayout.VERTICAL);
        menu.setGravity(Gravity.CENTER);
        menu.setPadding(dp(6), dp(6), dp(6), dp(2));
        menu.setBackground(roundRect(0x91050A12, 0x334DB6FF, dp(18), dp(1)));
        menu.setElevation(dp(9));
        menu.addView(tinyButton("Stats", 0xDF123143, new View.OnClickListener() {
            @Override public void onClick(View v) { feedback(v); showStatsDialog(); }
        }), miniButtonParams());
        menu.addView(tinyButton("Hall", 0xDF1B2545, new View.OnClickListener() {
            @Override public void onClick(View v) { feedback(v); showHallDialog(); }
        }), miniButtonParams());
        menu.addView(tinyButton("Regole", 0xDF123143, new View.OnClickListener() {
            @Override public void onClick(View v) { feedback(v); showRulesDialog(); }
        }), miniButtonParams());
        menu.addView(tinyButton("Tavoli", 0xDF1B2545, new View.OnClickListener() {
            @Override public void onClick(View v) { feedback(v); showTablesDialog(); }
        }), miniButtonParams());
        menu.addView(tinyButton("Trofei", 0xDF233A5E, new View.OnClickListener() {
            @Override public void onClick(View v) { feedback(v); showAchievementsDialog(); }
        }), miniButtonParams());
        menu.addView(tinyButton("Missioni", 0xDF233A5E, new View.OnClickListener() {
            @Override public void onClick(View v) { feedback(v); showMissionsDialog(); }
        }), miniButtonParams());
        menu.addView(tinyButton("Guida", 0xDF123143, new View.OnClickListener() {
            @Override public void onClick(View v) { feedback(v); showGuideDialog(); }
        }), miniButtonParams());
        menu.addView(tinyButton("Profili", 0xDF123143, new View.OnClickListener() {
            @Override public void onClick(View v) { feedback(v); showProfilesDialog(); }
        }), miniButtonParams());
        menu.addView(tinyButton("Audio", 0xDF1B2545, new View.OnClickListener() {
            @Override public void onClick(View v) { feedback(v); showOptionsDialog(); }
        }), miniButtonParams());
        menu.addView(tinyButton("Reset", 0xDF642235, new View.OnClickListener() {
            @Override public void onClick(View v) { feedback(v); confirmNewGame(); }
        }), miniButtonParams());
        tableLayer.addView(menu, anchoredParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.LEFT | Gravity.CENTER_VERTICAL, dp(compact() ? 8 : 18), 0, 0, dp(compact() ? 20 : 34)));
    }

    private void addDealerSeat() {
        boolean reveal = game.shouldRevealDealer() && !forceHideDealerHole;
        String state;
        if (game.dealerHand.isEmpty()) {
            state = "in attesa";
        } else if (reveal) {
            state = "totale " + Player.score(game.dealerHand);
        } else {
            state = "carta coperta";
        }
        LinearLayout seat = seatPanel(game.phase == BlackjackGame.Phase.DEALER_TURN, true);
        seat.addView(seatTitle("BANCO // " + state));
        addCardStrip(seat, game.dealerHand, !reveal && game.dealerHand.size() > 1, true, "dealer");
        tableLayer.addView(seat, anchoredParams(dealerWidth(), ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.TOP | Gravity.CENTER_HORIZONTAL, 0, dp(compact() ? 64 : 94), 0, 0));
    }

    private void addCpuSeats() {
        int position = 0;
        for (int i = 0; i < game.tablePlayers.size(); i++) {
            Player player = game.tablePlayers.get(i);
            if (!player.cpu) {
                continue;
            }
            boolean active = game.phase == BlackjackGame.Phase.CPU_TURN && i == game.activeCpuPlayerIndex;
            LinearLayout seat = playerSeat(player, false, active, "cpu-" + player.name + "-");
            tableLayer.addView(seat, cpuParams(position));
            if (active) {
                animateActive(seat);
            }
            position++;
        }
    }

    private void addHumanSeat() {
        LinearLayout seat = playerSeat(game.human, true, game.phase == BlackjackGame.Phase.PLAYER_TURN, "human-");
        tableLayer.addView(seat, anchoredParams(humanWidth(), ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL, 0, 0, 0, dockClearance()));
        if (game.phase == BlackjackGame.Phase.PLAYER_TURN) {
            animateActive(seat);
        }
    }

    private void addMessageBubble() {
        TextView msg = new TextView(this);
        msg.setText(currentMessage());
        msg.setTextColor(TEXT);
        msg.setTextSize(compact() ? 12 : 15);
        msg.setTypeface(Typeface.DEFAULT_BOLD);
        msg.setGravity(Gravity.CENTER);
        msg.setSingleLine(true);
        msg.setEllipsize(TextUtils.TruncateAt.END);
        msg.setPadding(dp(16), dp(9), dp(16), dp(9));
        msg.setBackground(gradientRect(0xD90B1020, 0xC10D2630, 0x6658E3FF, dp(20), dp(1)));
        msg.setElevation(dp(12));
        tableLayer.addView(msg, anchoredParams(messageWidth(), ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.CENTER, 0, 0, 0, dp(compact() ? 8 : 18)));
        if (animateCurrentRender) {
            animatePanelEntry(msg, 0);
        }
    }

    private String currentMessage() {
        if (tableBusy) {
            return cinematicMessage;
        }
        if (game.phase == BlackjackGame.Phase.PLAYER_TURN) {
            return "Tocca a te: guarda il banco e scegli la mossa.";
        }
        if (game.phase == BlackjackGame.Phase.INSURANCE) {
            return "Il banco mostra Asso: puoi prendere assicurazione.";
        }
        if (game.phase == BlackjackGame.Phase.ROUND_OVER) {
            return "Mano conclusa. Pronto per una nuova distribuzione.";
        }
        if (game.phase == BlackjackGame.Phase.GAME_OVER) {
            return "Partita terminata. Risultato registrato nella Hall.";
        }
        return cinematicMessage;
    }

    private LinearLayout playerSeat(Player player, boolean human, boolean active, String keyPrefix) {
        LinearLayout seat = seatPanel(active, false);
        String role = human ? "TU" : shortDifficulty(player.difficulty).toUpperCase(Locale.ITALY);
        seat.addView(seatTitle(role + " // " + player.name + " // " + BlackjackGame.money(player.balance)));

        HorizontalScrollView handsScroll = new HorizontalScrollView(this);
        handsScroll.setHorizontalScrollBarEnabled(false);
        handsScroll.setFillViewport(true);
        handsScroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        LinearLayout hands = new LinearLayout(this);
        hands.setOrientation(LinearLayout.HORIZONTAL);
        hands.setGravity(Gravity.CENTER);
        hands.setMinimumWidth(human ? humanWidth() : cpuWidth());

        for (int h = 0; h < player.hands.size(); h++) {
            LinearLayout handBox = new LinearLayout(this);
            handBox.setOrientation(LinearLayout.VERTICAL);
            handBox.setGravity(Gravity.CENTER);
            handBox.setPadding(dp(5), dp(2), dp(5), dp(4));
            handBox.setBackground(roundRect(0x36101826, 0x554DB6FF, dp(12), dp(1)));
            handBox.addView(handLabel(player, human, h));
            addCardStrip(handBox, player.hands.get(h), false, !human, keyPrefix + h);
            LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(handWidth(human), ViewGroup.LayoutParams.WRAP_CONTENT);
            hp.setMargins(0, 0, dp(6), 0);
            hands.addView(handBox, hp);
        }

        handsScroll.addView(hands, new HorizontalScrollView.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT));
        seat.addView(handsScroll, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT));
        return seat;
    }

    private String shortDifficulty(String difficulty) {
        if (difficulty == null || difficulty.length() == 0) {
            return "CPU";
        }
        return difficulty.length() <= 3 ? difficulty : difficulty.substring(0, 3);
    }

    private TextView handLabel(Player player, boolean human, int handIndex) {
        int total = Player.score(player.hands.get(handIndex));
        String state;
        if (player.hands.get(handIndex).isEmpty()) {
            state = "attesa";
        } else if (player.surrendered.get(handIndex).booleanValue()) {
            state = "resa";
        } else if (Player.isBlackjack(player.hands.get(handIndex))) {
            state = "BJ";
        } else if (total > 21) {
            state = "sballa";
        } else {
            state = String.valueOf(total);
        }
        String turn = human && game.phase == BlackjackGame.Phase.PLAYER_TURN && handIndex == game.activeHandIndex ? " *" : "";
        TextView label = new TextView(this);
        label.setText("MANO " + (handIndex + 1) + turn + "  |  " + state + "  |  " + BlackjackGame.money(player.bets.get(handIndex).intValue()));
        label.setTextColor(MUTED);
        label.setTextSize(compact() ? 9 : 11);
        label.setGravity(Gravity.CENTER);
        label.setSingleLine(true);
        label.setEllipsize(TextUtils.TruncateAt.END);
        return label;
    }

    private LinearLayout seatPanel(boolean active, boolean dealer) {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setGravity(Gravity.CENTER);
        panel.setPadding(dp(compact() ? 6 : 8), dp(compact() ? 5 : 7), dp(compact() ? 6 : 8), dp(compact() ? 5 : 7));
        int fillStart = dealer ? 0xB20A1220 : active ? 0xE30D3443 : 0x75101826;
        int fillEnd = dealer ? 0x95102034 : active ? 0xD41B2850 : 0x64100F1D;
        int stroke = active ? CYAN : dealer ? 0x88FFC85A : 0x554DB6FF;
        panel.setBackground(gradientRect(fillStart, fillEnd, stroke, dp(18), dp(active ? 2 : 1)));
        panel.setElevation(active ? dp(15) : dp(7));
        return panel;
    }

    private TextView seatTitle(String text) {
        TextView title = new TextView(this);
        title.setText(text);
        title.setTextColor(TEXT);
        title.setTextSize(compact() ? 10 : 13);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setGravity(Gravity.CENTER);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        title.setLetterSpacing(0.05f);
        title.setPadding(dp(2), 0, dp(2), dp(5));
        return title;
    }

    private void renderActions() {
        actionDock.removeAllViews();
        TextView phase = new TextView(this);
        phase.setText(phaseText());
        phase.setTextColor(TEXT);
        phase.setTextSize(compact() ? 10 : 12);
        phase.setTypeface(Typeface.DEFAULT_BOLD);
        phase.setGravity(Gravity.CENTER);
        phase.setLetterSpacing(0.08f);
        phase.setPadding(dp(8), dp(4), dp(8), dp(4));
        phase.setBackground(roundRect(0x65050A12, 0x244DB6FF, dp(13), dp(1)));
        actionDock.addView(phase, fullWidth(0, 0, 0, dp(3)));

        if (tableBusy || game.phase == BlackjackGame.Phase.DEALING
                || game.phase == BlackjackGame.Phase.CPU_TURN
                || game.phase == BlackjackGame.Phase.DEALER_TURN) {
            TextView busy = dockText(tableBusy ? cinematicMessage : "Il tavolo sta risolvendo la mano...");
            actionDock.addView(busy, fullWidth(0, 0, 0, dp(3)));
            appendTicker();
            return;
        }

        if (game.phase == BlackjackGame.Phase.BETTING || game.phase == BlackjackGame.Phase.ROUND_OVER) {
            renderBettingActions();
        } else if (game.phase == BlackjackGame.Phase.INSURANCE) {
            renderInsuranceActions();
        } else if (game.phase == BlackjackGame.Phase.PLAYER_TURN) {
            renderPlayerActions();
        } else if (game.phase == BlackjackGame.Phase.GAME_OVER) {
            renderGameOverActions();
        }
        appendTicker();
    }

    private void renderBettingActions() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER);
        row.addView(dockText("Main " + BlackjackGame.money(game.currentBet)), weighted(1.15f, dp(2), 0, dp(2), dp(4)));
        row.addView(actionButton("+10", 0xFF1B2636, new View.OnClickListener() {
            @Override public void onClick(View v) { game.changeBet(10); feedback(v); render(); }
        }), weighted(0.72f, dp(2), 0, dp(2), dp(4)));
        row.addView(actionButton("+25", 0xFF233A5E, new View.OnClickListener() {
            @Override public void onClick(View v) { game.changeBet(25); feedback(v); render(); }
        }), weighted(0.72f, dp(2), 0, dp(2), dp(4)));
        row.addView(actionButton("+100", 0xFF2D235E, new View.OnClickListener() {
            @Override public void onClick(View v) { game.changeBet(100); feedback(v); render(); }
        }), weighted(0.82f, dp(2), 0, dp(2), dp(4)));
        row.addView(actionButton("Ripeti", 0xFF1F3B4B, new View.OnClickListener() {
            @Override public void onClick(View v) { game.repeatLastBet(); feedback(v); render(); }
        }), weighted(0.9f, dp(2), 0, dp(2), dp(4)));
        row.addView(actionButton("Cancella", 0xFF43263A, new View.OnClickListener() {
            @Override public void onClick(View v) { game.clearBet(); game.clearSideBets(); feedback(v); render(); }
        }), weighted(1f, dp(2), 0, dp(2), dp(4)));
        row.addView(actionButton("All-in", 0xFF233A5E, new View.OnClickListener() {
            @Override public void onClick(View v) { game.setMaxBet(); feedback(v); render(); }
        }), weighted(0.85f, dp(2), 0, dp(2), dp(4)));
        Button deal = actionButton(game.phase == BlackjackGame.Phase.ROUND_OVER ? "Nuova mano" : "Deal", GOLD_DARK, new View.OnClickListener() {
            @Override public void onClick(View v) {
                feedback(v);
                if (game.prepareRound()) {
                    startInitialDealSequence();
                } else {
                    afterGameAction();
                }
            }
        });
        deal.setEnabled(game.canDeal());
        row.addView(deal, weighted(1.25f, dp(2), 0, dp(2), dp(4)));
        actionDock.addView(row, fullWidth(0, 0, 0, 0));

        LinearLayout side = new LinearLayout(this);
        side.setOrientation(LinearLayout.HORIZONTAL);
        side.setGravity(Gravity.CENTER);
        side.addView(dockText("Pairs " + BlackjackGame.money(game.sideBetPairs)), weighted(1f, dp(2), 0, dp(2), 0));
        side.addView(actionButton("+5", 0xFF284453, new View.OnClickListener() {
            @Override public void onClick(View v) { game.changeSideBet(true, 5); feedback(v); render(); }
        }), weighted(0.55f, dp(2), 0, dp(2), 0));
        side.addView(dockText("21+3 " + BlackjackGame.money(game.sideBetTwentyOneThree)), weighted(1f, dp(2), 0, dp(2), 0));
        side.addView(actionButton("+5", 0xFF284453, new View.OnClickListener() {
            @Override public void onClick(View v) { game.changeSideBet(false, 5); feedback(v); render(); }
        }), weighted(0.55f, dp(2), 0, dp(2), 0));
        if (game.phase == BlackjackGame.Phase.ROUND_OVER) {
            side.addView(actionButton("Riepilogo", 0xFF1F3B4B, new View.OnClickListener() {
                @Override public void onClick(View v) { feedback(v); showRoundSummaryDialog(); }
            }), weighted(1f, dp(2), 0, dp(2), 0));
        }
        actionDock.addView(side, fullWidth(0, 0, 0, 0));
    }

    private void renderInsuranceActions() {
        int maxInsurance = Math.min(game.human.bets.get(0).intValue() / 2, game.human.balance);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER);
        row.addView(dockText("Assicurazione max " + BlackjackGame.money(maxInsurance)), weighted(1.6f, dp(2), 0, dp(2), 0));
        Button yes = actionButton("Assicurati", GOLD_DARK, new View.OnClickListener() {
            @Override public void onClick(View v) { feedback(v); game.answerInsurance(true); afterGameAction(); }
        });
        yes.setEnabled(maxInsurance > 0);
        row.addView(yes, weighted(1f, dp(2), 0, dp(2), 0));
        row.addView(actionButton("No", 0xFF1B2636, new View.OnClickListener() {
            @Override public void onClick(View v) { feedback(v); game.answerInsurance(false); afterGameAction(); }
        }), weighted(0.75f, dp(2), 0, dp(2), 0));
        actionDock.addView(row, fullWidth(0, 0, 0, 0));
    }

    private void renderPlayerActions() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER);
        Button hit = actionButton("Pesca", GREEN, new View.OnClickListener() {
            @Override public void onClick(View v) { feedback(v); game.hit(); afterGameAction(); }
        });
        hit.setEnabled(game.canHit());
        row.addView(hit, weighted(1f, dp(2), 0, dp(2), dp(4)));
        Button stand = actionButton("Stai", 0xFF1B2636, new View.OnClickListener() {
            @Override public void onClick(View v) { feedback(v); game.stand(); afterGameAction(); }
        });
        stand.setEnabled(game.canStand());
        row.addView(stand, weighted(1f, dp(2), 0, dp(2), dp(4)));
        Button dbl = actionButton("Raddoppia", GOLD_DARK, new View.OnClickListener() {
            @Override public void onClick(View v) { feedback(v); game.doubleDown(); afterGameAction(); }
        });
        dbl.setEnabled(game.canDouble());
        row.addView(dbl, weighted(1.25f, dp(2), 0, dp(2), dp(4)));
        Button split = actionButton("Dividi", BLUE, new View.OnClickListener() {
            @Override public void onClick(View v) { feedback(v); game.split(); afterGameAction(); }
        });
        split.setEnabled(game.canSplit());
        row.addView(split, weighted(1f, dp(2), 0, dp(2), dp(4)));
        Button surrender = actionButton("Arrenditi", RED, new View.OnClickListener() {
            @Override public void onClick(View v) { feedback(v); game.surrender(); afterGameAction(); }
        });
        surrender.setEnabled(game.canSurrender());
        row.addView(surrender, weighted(1.25f, dp(2), 0, dp(2), dp(4)));
        row.addView(actionButton("Consiglio", 0xFF284453, new View.OnClickListener() {
            @Override public void onClick(View v) { feedback(v); showInfoDialog("Suggerimento", game.strategyHint()); }
        }), weighted(1.25f, dp(2), 0, dp(2), dp(4)));
        actionDock.addView(row, fullWidth(0, 0, 0, 0));
    }

    private void renderGameOverActions() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.addView(dockText("Partita terminata. Risultato salvato."), weighted(1.6f, dp(2), 0, dp(2), 0));
        row.addView(actionButton("Nuova partita", GOLD_DARK, new View.OnClickListener() {
            @Override public void onClick(View v) { feedback(v); startFreshGame(); }
        }), weighted(1f, dp(2), 0, dp(2), 0));
        actionDock.addView(row, fullWidth(0, 0, 0, 0));
    }

    private TextView dockText(String text) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextColor(MUTED);
        view.setTextSize(compact() ? 10 : 12);
        view.setTypeface(Typeface.DEFAULT_BOLD);
        view.setGravity(Gravity.CENTER);
        view.setPadding(dp(10), dp(6), dp(10), dp(6));
        view.setSingleLine(true);
        view.setEllipsize(TextUtils.TruncateAt.END);
        view.setBackground(roundRect(0x58050A12, 0x224DB6FF, dp(14), dp(1)));
        return view;
    }

    private void appendTicker() {
        detachFromParent(logTicker);
        logTicker.setText(game.eventText().replace('\n', ' '));
        actionDock.addView(logTicker, fullWidth(0, dp(2), 0, 0));
    }

    private Button actionButton(String text, int color, View.OnClickListener listener) {
        Button button = new Button(this);
        button.setText(text);
        button.setTextColor(TEXT);
        button.setTextSize(compact() ? 10 : 12);
        button.setAllCaps(false);
        button.setMinHeight(dp(compact() ? 34 : 40));
        button.setGravity(Gravity.CENTER);
        button.setPadding(dp(6), 0, dp(6), 0);
        button.setTypeface(Typeface.DEFAULT_BOLD);
        button.setBackground(gradientRect(color, shade(color, 0.64f), 0x44FFFFFF, dp(15), dp(1)));
        button.setElevation(dp(4));
        button.setOnClickListener(listener);
        return button;
    }

    private Button tinyButton(String text, int color, View.OnClickListener listener) {
        Button button = actionButton(text, color, listener);
        button.setTextSize(compact() ? 9 : 10);
        button.setMinHeight(dp(compact() ? 28 : 32));
        return button;
    }

    private void addCardStrip(LinearLayout parent, List<Card> cards, boolean hideLast, boolean compactCards, String rowKey) {
        if (cards == null || cards.isEmpty()) {
            lastCardCounts.put(rowKey, Integer.valueOf(0));
            lastHiddenRows.put(rowKey, Boolean.valueOf(hideLast));
            TextView empty = new TextView(this);
            empty.setText("Slot carte vuoto");
            empty.setTextColor(MUTED);
            empty.setTextSize(compact() ? 9 : 11);
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(dp(6), dp(8), dp(6), dp(8));
            empty.setBackground(roundRect(0x25050A12, 0x224DB6FF, dp(10), dp(1)));
            parent.addView(empty, fullWidth(0, dp(3), 0, dp(2)));
            return;
        }

        Integer previousCount = lastCardCounts.get(rowKey);
        Boolean previousHidden = lastHiddenRows.get(rowKey);
        boolean hiddenWasRevealed = previousHidden != null && previousHidden.booleanValue() && !hideLast;

        HorizontalScrollView scroll = new HorizontalScrollView(this);
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.setFillViewport(true);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER);
        row.setOrientation(LinearLayout.HORIZONTAL);

        int cw = cardWidthForCount(compactCards, rowKey, cards.size());
        int stripWidth = cardStripWidth(rowKey, compactCards);
        int rightMargin = cardRightMarginForCount(cards.size(), cw, stripWidth);
        row.setMinimumWidth(stripWidth);
        for (int i = 0; i < cards.size(); i++) {
            boolean hidden = hideLast && i == cards.size() - 1;
            PlayingCardView cardView = new PlayingCardView(this, cards.get(i), hidden);
            LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(cw, (int) (cw * 1.43f));
            cp.setMargins(dp(2), dp(2), i == cards.size() - 1 ? dp(2) : rightMargin, dp(2));
            row.addView(cardView, cp);
            boolean newCard = previousCount == null || i >= previousCount.intValue();
            boolean flippedDealerCard = hiddenWasRevealed && i == cards.size() - 1;
            if (flippedDealerCard) {
                animateCardFlip(cardView, i);
            } else if (animateCurrentRender && newCard) {
                animateCardDeal(cardView, i, compactCards);
            }
        }
        scroll.addView(row, new HorizontalScrollView.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT));
        parent.addView(scroll, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT));
        if (cards.size() > 3) {
            scroll.post(new Runnable() {
                @Override public void run() {
                    scroll.fullScroll(View.FOCUS_RIGHT);
                }
            });
        }
        lastCardCounts.put(rowKey, Integer.valueOf(cards.size()));
        lastHiddenRows.put(rowKey, Boolean.valueOf(hideLast));
    }

    private void startInitialDealSequence() {
        handler.removeCallbacksAndMessages(null);
        resetCardMemory();
        dealStep = 0;
        tableBusy = true;
        forceHideDealerHole = true;
        cinematicMessage = "Il mazziere taglia il mazzo e raccoglie le puntate...";
        animateNextRender = true;
        render();
        handler.postDelayed(new Runnable() {
            @Override public void run() { runInitialDealStep(); }
        }, delay(650L));
    }

    private void runInitialDealStep() {
        if (game.phase != BlackjackGame.Phase.DEALING) {
            tableBusy = false;
            render();
            return;
        }
        int players = game.tablePlayers.size();
        int firstDealerStep = players;
        int secondPlayersStart = players + 1;
        int secondDealerStep = players * 2 + 1;

        if (dealStep < players) {
            Player player = game.tablePlayers.get(dealStep);
            Card card = game.dealCardToPlayer(dealStep, 0);
            cinematicMessage = "Carta a " + displayName(player) + ": " + card.label();
        } else if (dealStep == firstDealerStep) {
            Card card = game.dealCardToDealer();
            cinematicMessage = "Prima carta del banco: " + card.label();
        } else if (dealStep >= secondPlayersStart && dealStep < secondDealerStep) {
            int playerIndex = dealStep - secondPlayersStart;
            Player player = game.tablePlayers.get(playerIndex);
            Card card = game.dealCardToPlayer(playerIndex, 0);
            cinematicMessage = "Seconda carta a " + displayName(player) + ": " + card.label();
        } else if (dealStep == secondDealerStep) {
            game.dealCardToDealer();
            cinematicMessage = "Carta coperta al banco. Il tavolo si ferma un attimo.";
        } else {
            game.completeInitialDeal();
            tableBusy = false;
            afterGameAction();
            return;
        }

        dealStep++;
        animateNextRender = true;
        render();
        handler.postDelayed(new Runnable() {
            @Override public void run() { runInitialDealStep(); }
        }, delay(dealStep > secondDealerStep ? 900L : 520L));
    }

    private void startCpuSequence() {
        tableBusy = true;
        forceHideDealerHole = true;
        cinematicMessage = "Gli altri giocatori studiano il banco...";
        animateNextRender = true;
        render();
        handler.postDelayed(new Runnable() {
            @Override public void run() { runCpuStep(); }
        }, delay(850L));
    }

    private void runCpuStep() {
        if (game.phase != BlackjackGame.Phase.CPU_TURN) {
            tableBusy = false;
            afterGameAction();
            return;
        }
        BlackjackGame.TableAction action = game.playNextCpuStep();
        animateNextRender = true;
        if (action != null) {
            cinematicMessage = action.message;
            render();
            handler.postDelayed(new Runnable() {
                @Override public void run() { runCpuStep(); }
            }, delay(Math.max(650, action.delayMs)));
        } else {
            startDealerSequence();
        }
    }

    private void startDealerSequence() {
        tableBusy = true;
        forceHideDealerHole = game.dealerHand.size() > 1;
        cinematicMessage = "Il mazziere posa la mano sulla carta coperta...";
        animateNextRender = true;
        render();
        handler.postDelayed(new Runnable() {
            @Override public void run() { revealDealerCard(); }
        }, delay(1200L));
    }

    private void revealDealerCard() {
        forceHideDealerHole = false;
        if (game.dealerHand.size() > 1) {
            cinematicMessage = "Il banco gira " + game.dealerHand.get(1).label()
                + ". Totale banco: " + Player.score(game.dealerHand) + ".";
        } else {
            cinematicMessage = "Il banco scopre la propria mano.";
        }
        animateNextRender = true;
        render();
        handler.postDelayed(new Runnable() {
            @Override public void run() { runDealerStep(); }
        }, delay(game.dealerHasBlackjack() ? 1400L : 950L));
    }

    private void runDealerStep() {
        forceHideDealerHole = false;
        if (game.dealerHasBlackjack()) {
            cinematicMessage = "Blackjack del banco. Il tavolo trattiene il fiato...";
            game.settleCurrentRound(true);
            finishAnimatedRound();
            return;
        }
        if (!game.hasLiveHandsForDealer()) {
            cinematicMessage = "Tutte le mani sono chiuse. Il banco non pesca.";
            game.settleCurrentRound(false);
            finishAnimatedRound();
            return;
        }
        if (game.dealerShouldDraw()) {
            Card card = game.dealerDrawStep();
            cinematicMessage = "Il banco pesca: esce " + card.label()
                + " (" + Player.score(game.dealerHand) + ").";
            animateNextRender = true;
            render();
            handler.postDelayed(new Runnable() {
                @Override public void run() { runDealerStep(); }
            }, delay(1150L));
        } else {
            cinematicMessage = "Il banco resta con " + Player.score(game.dealerHand) + ". Si pagano le mani.";
            game.settleCurrentRound(false);
            finishAnimatedRound();
        }
    }

    private void finishAnimatedRound() {
        tableBusy = false;
        forceHideDealerHole = false;
        if (game.phase == BlackjackGame.Phase.GAME_OVER && !game.hallRecorded) {
            appendHallEntry();
            game.hallRecorded = true;
        }
        if (game.phase == BlackjackGame.Phase.ROUND_OVER || game.phase == BlackjackGame.Phase.GAME_OVER) {
            persistProgress();
        }
        animateNextRender = true;
        render();
    }

    private void afterGameAction() {
        animateNextRender = true;
        if (game.phase == BlackjackGame.Phase.GAME_OVER && !game.hallRecorded) {
            appendHallEntry();
            game.hallRecorded = true;
        }
        if (game.phase == BlackjackGame.Phase.CPU_TURN) {
            startCpuSequence();
            return;
        }
        if (game.phase == BlackjackGame.Phase.DEALER_TURN) {
            startDealerSequence();
            return;
        }
        if (game.phase == BlackjackGame.Phase.ROUND_OVER || game.phase == BlackjackGame.Phase.GAME_OVER) {
            persistProgress();
        }
        tableBusy = false;
        render();
    }

    private void restoreFromPrefs() {
        Player.Stats stats = new Player.Stats();
        stats.hands = prefInt("stats_hands", 0);
        stats.wins = prefInt("stats_wins", 0);
        stats.losses = prefInt("stats_losses", 0);
        stats.pushes = prefInt("stats_pushes", 0);
        stats.busts = prefInt("stats_busts", 0);
        stats.blackjacks = prefInt("stats_blackjacks", 0);
        stats.doubles = prefInt("stats_doubles", 0);
        stats.splits = prefInt("stats_splits", 0);
        stats.surrenders = prefInt("stats_surrenders", 0);
        stats.insuranceWon = prefInt("stats_insurance", 0);
        stats.sideBetsWon = prefInt("stats_side_bets", 0);
        stats.net = prefInt("stats_net", 0);
        int savedTableIndex = prefInt("table_index", 0);
        game.rules.decks = prefInt("rules_decks", BlackjackGame.NUM_DECKS);
        game.rules.blackjackPaysSixToFive = prefs.getBoolean(prefKey("rules_6_5"), prefs.getBoolean("rules_6_5", false));
        game.rules.dealerHitsSoft17 = prefs.getBoolean(prefKey("rules_h17"), prefs.getBoolean("rules_h17", false));
        game.rules.surrenderEnabled = prefs.getBoolean(prefKey("rules_surrender"), prefs.getBoolean("rules_surrender", true));
        game.rules.doubleAfterSplit = prefs.getBoolean(prefKey("rules_das"), prefs.getBoolean("rules_das", true));
        game.sideBetPairs = prefInt("side_pairs", 0);
        game.sideBetTwentyOneThree = prefInt("side_213", 0);
        game.winStreak = prefInt("win_streak", 0);
        game.bestBalance = prefInt("best_balance", BlackjackGame.START_BALANCE);
        game.importAchievements(prefs.getString(prefKey("achievements"), prefs.getString("achievements", "")));
        game.restoreProgress(
            prefInt("balance", BlackjackGame.START_BALANCE),
            prefInt("dealer_bank", BlackjackGame.START_DEALER_BANK),
            prefInt("current_bet", BlackjackGame.MIN_BET),
            stats);
        game.selectTable(savedTableIndex);
    }

    private void persistProgress() {
        Player.Stats stats = game.human.stats;
        prefs.edit()
            .putString("name", game.human.name)
            .putString("active_profile", currentProfile)
            .putString("profiles", profilesWith(currentProfile))
            .putString(prefKey("name"), game.human.name)
            .putInt(prefKey("balance"), game.human.balance)
            .putInt(prefKey("dealer_bank"), game.dealerBankroll)
            .putInt(prefKey("current_bet"), game.currentBet)
            .putInt(prefKey("side_pairs"), game.sideBetPairs)
            .putInt(prefKey("side_213"), game.sideBetTwentyOneThree)
            .putInt(prefKey("table_index"), game.tableIndex)
            .putInt(prefKey("rules_decks"), game.rules.decks)
            .putBoolean(prefKey("rules_6_5"), game.rules.blackjackPaysSixToFive)
            .putBoolean(prefKey("rules_h17"), game.rules.dealerHitsSoft17)
            .putBoolean(prefKey("rules_surrender"), game.rules.surrenderEnabled)
            .putBoolean(prefKey("rules_das"), game.rules.doubleAfterSplit)
            .putInt(prefKey("best_balance"), game.bestBalance)
            .putInt(prefKey("win_streak"), game.winStreak)
            .putString(prefKey("achievements"), game.exportAchievements())
            .putInt(prefKey("stats_hands"), stats.hands)
            .putInt(prefKey("stats_wins"), stats.wins)
            .putInt(prefKey("stats_losses"), stats.losses)
            .putInt(prefKey("stats_pushes"), stats.pushes)
            .putInt(prefKey("stats_busts"), stats.busts)
            .putInt(prefKey("stats_blackjacks"), stats.blackjacks)
            .putInt(prefKey("stats_doubles"), stats.doubles)
            .putInt(prefKey("stats_splits"), stats.splits)
            .putInt(prefKey("stats_surrenders"), stats.surrenders)
            .putInt(prefKey("stats_insurance"), stats.insuranceWon)
            .putInt(prefKey("stats_side_bets"), stats.sideBetsWon)
            .putInt(prefKey("stats_net"), stats.net)
            .putInt("balance", game.human.balance)
            .putInt("dealer_bank", game.dealerBankroll)
            .putInt("current_bet", game.currentBet)
            .putInt("stats_hands", stats.hands)
            .putInt("stats_wins", stats.wins)
            .putInt("stats_losses", stats.losses)
            .putInt("stats_pushes", stats.pushes)
            .putInt("stats_busts", stats.busts)
            .putInt("stats_blackjacks", stats.blackjacks)
            .putInt("stats_doubles", stats.doubles)
            .putInt("stats_splits", stats.splits)
            .putInt("stats_surrenders", stats.surrenders)
            .putInt("stats_insurance", stats.insuranceWon)
            .putInt("stats_side_bets", stats.sideBetsWon)
            .putInt("stats_net", stats.net)
            .apply();
    }

    private void loadAppSettings() {
        soundEnabled = prefs.getBoolean("sound_enabled", true);
        vibrationEnabled = prefs.getBoolean("vibration_enabled", true);
        fastAnimations = prefs.getBoolean("fast_animations", false);
    }

    private void persistAppSettings() {
        prefs.edit()
            .putBoolean("sound_enabled", soundEnabled)
            .putBoolean("vibration_enabled", vibrationEnabled)
            .putBoolean("fast_animations", fastAnimations)
            .apply();
    }

    private int prefInt(String key, int fallback) {
        return prefs.getInt(prefKey(key), prefs.getInt(key, fallback));
    }

    private String prefKey(String key) {
        return "profile_" + safeProfile(currentProfile) + "_" + key;
    }

    private String safeProfile(String name) {
        String value = name == null ? "Giocatore" : name.trim();
        if (value.length() == 0) {
            value = "Giocatore";
        }
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isLetterOrDigit(c)) {
                builder.append(Character.toLowerCase(c));
            } else {
                builder.append('_');
            }
        }
        return builder.toString();
    }

    private String cleanProfile(String name) {
        if (name == null) {
            return "Giocatore";
        }
        String trimmed = name.trim();
        return trimmed.length() == 0 ? "Giocatore" : trimmed;
    }

    private String profilesWith(String profile) {
        String list = prefs.getString("profiles", "");
        if (profile == null || profile.trim().length() == 0) {
            return list;
        }
        String[] items = list.split("\\|");
        for (int i = 0; i < items.length; i++) {
            if (profile.equals(items[i])) {
                return list;
            }
        }
        return list.length() == 0 ? profile : list + "|" + profile;
    }

    private void switchProfile(String profile) {
        persistProgress();
        currentProfile = cleanProfile(profile);
        prefs.edit()
            .putString("active_profile", currentProfile)
            .putString("profiles", profilesWith(currentProfile))
            .apply();
        game = new BlackjackGame(currentProfile);
        if (prefs.contains(prefKey("balance"))) {
            restoreFromPrefs();
        }
        resetAnimationState();
        cinematicMessage = "Profilo " + currentProfile + " caricato. Scegli la puntata.";
        persistProgress();
        render();
    }

    private void appendHallEntry() {
        String old = prefs.getString("hall", "");
        String date = new SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.ITALY).format(new Date());
        String reason = game.dealerBankroll <= 0 ? "Banco battuto" : "Credito esaurito";
        String entry = date + " - " + game.human.name
            + " - saldo " + BlackjackGame.money(game.human.balance)
            + " - banco " + BlackjackGame.money(game.dealerBankroll)
            + " - " + reason + "\n";
        prefs.edit().putString("hall", entry + old).apply();
    }

    private void showNameDialog() {
        final Dialog dialog = new Dialog(this);
        LinearLayout box = dialogBox();
        box.addView(dialogTitle("Benvenuto al Blackjack Royal"));
        box.addView(dialogMessage("Inserisci il nome del giocatore per sederti al tavolo."), fullWidth(0, 0, 0, dp(10)));
        final EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        input.setHint("Il tuo nome");
        input.setTextColor(TEXT);
        input.setHintTextColor(MUTED);
        input.setTextSize(18);
        input.setPadding(dp(12), dp(8), dp(12), dp(8));
        input.setBackground(gradientRect(0xD90B1220, 0xC60E2530, 0x6632D5FF, dp(14), dp(1)));
        box.addView(input, fullWidth(0, 0, 0, dp(12)));
        box.addView(actionButton("Siediti al tavolo", GOLD_DARK, new View.OnClickListener() {
            @Override public void onClick(View v) {
                feedback(v);
                currentProfile = cleanProfile(input.getText().toString());
                game.newSession(currentProfile);
                resetAnimationState();
                persistProgress();
                dialog.dismiss();
                render();
            }
        }), fullWidth(0, 0, 0, 0));
        showCasinoDialog(dialog, box, false);
    }

    private void showStatsDialog() {
        showInfoDialog("Statistiche", game.statsText());
    }

    private void showHallDialog() {
        String hall = prefs.getString("hall", "");
        if (hall.length() == 0) {
            hall = "Nessuna partita conclusa ancora.";
        }
        showInfoDialog("Hall of Fame", hall);
    }

    private void showRoundSummaryDialog() {
        showInfoDialog("Riepilogo mano", game.lastRoundSummary);
    }

    private void showAchievementsDialog() {
        showInfoDialog("Trofei", game.achievementsText());
    }

    private void showMissionsDialog() {
        showInfoDialog("Missioni", game.missionsText());
    }

    private void showGuideDialog() {
        showInfoDialog("Guida rapida", game.guideText());
    }

    private void showRulesDialog() {
        final Dialog dialog = new Dialog(this);
        LinearLayout box = dialogBox();
        box.addView(dialogTitle("Regole tavolo"));
        box.addView(dialogMessage(game.rulesText()), fullWidth(0, 0, 0, dp(10)));

        LinearLayout row1 = new LinearLayout(this);
        row1.setOrientation(LinearLayout.HORIZONTAL);
        row1.addView(actionButton("Mazzi -", 0xFF1B2636, new View.OnClickListener() {
            @Override public void onClick(View v) { feedback(v); game.configureRules(game.rules.decks - 1, game.rules.blackjackPaysSixToFive, game.rules.dealerHitsSoft17, game.rules.surrenderEnabled, game.rules.doubleAfterSplit); persistProgress(); dialog.dismiss(); showRulesDialog(); }
        }), weighted(1f, dp(2), 0, dp(2), dp(4)));
        row1.addView(actionButton("Mazzi +", 0xFF1B2636, new View.OnClickListener() {
            @Override public void onClick(View v) { feedback(v); game.configureRules(game.rules.decks + 1, game.rules.blackjackPaysSixToFive, game.rules.dealerHitsSoft17, game.rules.surrenderEnabled, game.rules.doubleAfterSplit); persistProgress(); dialog.dismiss(); showRulesDialog(); }
        }), weighted(1f, dp(2), 0, dp(2), dp(4)));
        box.addView(row1);

        LinearLayout row2 = new LinearLayout(this);
        row2.setOrientation(LinearLayout.HORIZONTAL);
        row2.addView(actionButton(game.rules.blackjackPaysSixToFive ? "BJ 6:5" : "BJ 3:2", GOLD_DARK, new View.OnClickListener() {
            @Override public void onClick(View v) { feedback(v); game.configureRules(game.rules.decks, !game.rules.blackjackPaysSixToFive, game.rules.dealerHitsSoft17, game.rules.surrenderEnabled, game.rules.doubleAfterSplit); persistProgress(); dialog.dismiss(); showRulesDialog(); }
        }), weighted(1f, dp(2), 0, dp(2), dp(4)));
        row2.addView(actionButton(game.rules.dealerHitsSoft17 ? "Soft17 pesca" : "Soft17 sta", 0xFF284453, new View.OnClickListener() {
            @Override public void onClick(View v) { feedback(v); game.configureRules(game.rules.decks, game.rules.blackjackPaysSixToFive, !game.rules.dealerHitsSoft17, game.rules.surrenderEnabled, game.rules.doubleAfterSplit); persistProgress(); dialog.dismiss(); showRulesDialog(); }
        }), weighted(1f, dp(2), 0, dp(2), dp(4)));
        box.addView(row2);

        LinearLayout row3 = new LinearLayout(this);
        row3.setOrientation(LinearLayout.HORIZONTAL);
        row3.addView(actionButton(game.rules.surrenderEnabled ? "Surrender on" : "Surrender off", 0xFF1F3B4B, new View.OnClickListener() {
            @Override public void onClick(View v) { feedback(v); game.configureRules(game.rules.decks, game.rules.blackjackPaysSixToFive, game.rules.dealerHitsSoft17, !game.rules.surrenderEnabled, game.rules.doubleAfterSplit); persistProgress(); dialog.dismiss(); showRulesDialog(); }
        }), weighted(1f, dp(2), 0, dp(2), dp(4)));
        row3.addView(actionButton(game.rules.doubleAfterSplit ? "DAS on" : "DAS off", 0xFF1F3B4B, new View.OnClickListener() {
            @Override public void onClick(View v) { feedback(v); game.configureRules(game.rules.decks, game.rules.blackjackPaysSixToFive, game.rules.dealerHitsSoft17, game.rules.surrenderEnabled, !game.rules.doubleAfterSplit); persistProgress(); dialog.dismiss(); showRulesDialog(); }
        }), weighted(1f, dp(2), 0, dp(2), dp(4)));
        box.addView(row3);
        box.addView(actionButton("Chiudi", GOLD_DARK, new View.OnClickListener() {
            @Override public void onClick(View v) { feedback(v); dialog.dismiss(); render(); }
        }), fullWidth(0, dp(8), 0, 0));
        showCasinoDialog(dialog, box, true);
    }

    private void showTablesDialog() {
        final Dialog dialog = new Dialog(this);
        LinearLayout box = dialogBox();
        box.addView(dialogTitle("Tavoli carriera"));
        box.addView(dialogMessage(game.careerText()), fullWidth(0, 0, 0, dp(10)));
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        for (int i = 0; i < BlackjackGame.Rules.TABLE_NAMES.length; i++) {
            final int index = i;
            Button table = actionButton(BlackjackGame.Rules.TABLE_NAMES[i], i == game.tableIndex ? GOLD_DARK : 0xFF1B2636, new View.OnClickListener() {
                @Override public void onClick(View v) { feedback(v); game.selectTable(index); persistProgress(); dialog.dismiss(); render(); }
            });
            row.addView(table, weighted(1f, dp(2), 0, dp(2), 0));
        }
        box.addView(row);
        showCasinoDialog(dialog, box, true);
    }

    private void showOptionsDialog() {
        final Dialog dialog = new Dialog(this);
        LinearLayout box = dialogBox();
        box.addView(dialogTitle("Opzioni"));
        box.addView(dialogMessage("Audio: " + (soundEnabled ? "on" : "off")
            + "\nVibrazione: " + (vibrationEnabled ? "on" : "off")
            + "\nAnimazioni: " + (fastAnimations ? "rapide" : "cinematiche")), fullWidth(0, 0, 0, dp(10)));
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.addView(actionButton("Audio", 0xFF1B2636, new View.OnClickListener() {
            @Override public void onClick(View v) { soundEnabled = !soundEnabled; feedback(v); persistAppSettings(); dialog.dismiss(); showOptionsDialog(); }
        }), weighted(1f, dp(2), 0, dp(2), 0));
        row.addView(actionButton("Vibra", 0xFF1B2636, new View.OnClickListener() {
            @Override public void onClick(View v) { vibrationEnabled = !vibrationEnabled; feedback(v); persistAppSettings(); dialog.dismiss(); showOptionsDialog(); }
        }), weighted(1f, dp(2), 0, dp(2), 0));
        row.addView(actionButton("Velocita", 0xFF1B2636, new View.OnClickListener() {
            @Override public void onClick(View v) { fastAnimations = !fastAnimations; feedback(v); persistAppSettings(); dialog.dismiss(); showOptionsDialog(); }
        }), weighted(1f, dp(2), 0, dp(2), 0));
        box.addView(row);
        showCasinoDialog(dialog, box, true);
    }

    private void showProfilesDialog() {
        final Dialog dialog = new Dialog(this);
        LinearLayout box = dialogBox();
        box.addView(dialogTitle("Profili"));
        box.addView(dialogMessage("Profilo attivo: " + currentProfile), fullWidth(0, 0, 0, dp(8)));
        String list = profilesWith(currentProfile);
        String[] profiles = list.split("\\|");
        for (int i = 0; i < profiles.length; i++) {
            final String profile = profiles[i];
            if (profile.trim().length() == 0) {
                continue;
            }
            box.addView(actionButton(profile, profile.equals(currentProfile) ? GOLD_DARK : 0xFF1B2636, new View.OnClickListener() {
                @Override public void onClick(View v) { feedback(v); switchProfile(profile); dialog.dismiss(); }
            }), fullWidth(0, 0, 0, dp(5)));
        }
        final EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setHint("Nuovo profilo");
        input.setTextColor(TEXT);
        input.setHintTextColor(MUTED);
        input.setBackground(gradientRect(0xD90B1220, 0xC60E2530, 0x6632D5FF, dp(14), dp(1)));
        box.addView(input, fullWidth(0, dp(8), 0, dp(8)));
        box.addView(actionButton("Crea / passa", GOLD_DARK, new View.OnClickListener() {
            @Override public void onClick(View v) { feedback(v); switchProfile(cleanProfile(input.getText().toString())); dialog.dismiss(); }
        }), fullWidth(0, 0, 0, 0));
        showCasinoDialog(dialog, box, true);
    }

    private void confirmNewGame() {
        final Dialog dialog = new Dialog(this);
        LinearLayout box = dialogBox();
        box.addView(dialogTitle("Nuova partita"));
        box.addView(dialogMessage("Azzerare saldo e statistiche mantenendo lo stesso nome?"), fullWidth(0, 0, 0, dp(12)));
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.addView(actionButton("Annulla", 0xFF31473C, new View.OnClickListener() {
            @Override public void onClick(View v) { feedback(v); dialog.dismiss(); }
        }), weighted(1f, dp(2), 0, dp(2), 0));
        row.addView(actionButton("Reset", RED, new View.OnClickListener() {
            @Override public void onClick(View v) { feedback(v); dialog.dismiss(); startFreshGame(); }
        }), weighted(1f, dp(2), 0, dp(2), 0));
        box.addView(row);
        showCasinoDialog(dialog, box, true);
    }

    private void showInfoDialog(String titleText, String bodyText) {
        final Dialog dialog = new Dialog(this);
        LinearLayout box = dialogBox();
        box.addView(dialogTitle(titleText));
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(false);
        scroll.setOverScrollMode(View.OVER_SCROLL_IF_CONTENT_SCROLLS);
        TextView body = dialogMessage(bodyText);
        body.setGravity(Gravity.LEFT);
        scroll.addView(body, new ScrollView.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT));
        int bodyHeight = Math.min(dp(compact() ? 170 : 280), Math.max(dp(120), screenHeight() - dp(150)));
        box.addView(scroll, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            bodyHeight));
        box.addView(actionButton("Chiudi", GOLD_DARK, new View.OnClickListener() {
            @Override public void onClick(View v) { feedback(v); dialog.dismiss(); }
        }), fullWidth(0, dp(12), 0, 0));
        showCasinoDialog(dialog, box, true);
    }

    private LinearLayout dialogBox() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(22), dp(18), dp(22), dp(18));
        box.setBackground(gradientRect(0xF4080D18, 0xF3122634, 0x7732D5FF, dp(24), dp(1)));
        box.setElevation(dp(18));
        return box;
    }

    private TextView dialogTitle(String text) {
        TextView title = new TextView(this);
        title.setText(text);
        title.setTextColor(TEXT);
        title.setTextSize(compact() ? 20 : 24);
        title.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD));
        title.setLetterSpacing(0.06f);
        title.setGravity(Gravity.CENTER);
        title.setPadding(0, 0, 0, dp(10));
        return title;
    }

    private TextView dialogMessage(String text) {
        TextView message = new TextView(this);
        message.setText(text);
        message.setTextColor(MUTED);
        message.setTextSize(compact() ? 14 : 15);
        message.setLineSpacing(dp(2), 1f);
        message.setGravity(Gravity.CENTER);
        return message;
    }

    private void showCasinoDialog(Dialog dialog, View view, boolean cancelable) {
        dialog.setContentView(view);
        dialog.setCancelable(cancelable);
        dialog.show();
        Window shown = dialog.getWindow();
        if (shown != null) {
            applyFullscreen(shown);
            shown.setBackgroundDrawableResource(android.R.color.transparent);
            int maxWidth = Math.max(dp(280), screenWidth() - dp(24));
            shown.setLayout(Math.min(dp(compact() ? 360 : 470), maxWidth), ViewGroup.LayoutParams.WRAP_CONTENT);
        }
    }

    private void startFreshGame() {
        handler.removeCallbacksAndMessages(null);
        tableBusy = false;
        forceHideDealerHole = true;
        cinematicMessage = "Nuovo tavolo pronto. Scegli la puntata.";
        game.newSession(game.human.name);
        resetAnimationState();
        persistProgress();
        render();
    }

    private String displayName(Player player) {
        return player.cpu ? player.name : "te";
    }

    private long delay(long ms) {
        return fastAnimations ? Math.max(120L, (long) (ms * 0.55f)) : ms;
    }

    private String phaseText() {
        if (game.phase == BlackjackGame.Phase.BETTING) return "Fase puntate";
        if (game.phase == BlackjackGame.Phase.DEALING) return "Distribuzione";
        if (game.phase == BlackjackGame.Phase.INSURANCE) return "Assicurazione";
        if (game.phase == BlackjackGame.Phase.PLAYER_TURN) return "Il tuo turno";
        if (game.phase == BlackjackGame.Phase.CPU_TURN) return "Avversari";
        if (game.phase == BlackjackGame.Phase.ROUND_OVER) return "Mano conclusa";
        if (game.phase == BlackjackGame.Phase.GAME_OVER) return "Game over";
        return "Banco";
    }

    private void animateCardDeal(final View view, int index, boolean compactCards) {
        view.setAlpha(0f);
        view.setTranslationY(compactCards ? -dp(16) : -dp(28));
        view.setTranslationX(dp(18));
        view.setRotation(index % 2 == 0 ? -8f : 8f);
        view.setScaleX(0.82f);
        view.setScaleY(0.82f);
        view.animate()
            .alpha(1f)
            .translationX(0f)
            .translationY(0f)
            .rotation(0f)
            .scaleX(1f)
            .scaleY(1f)
            .setStartDelay(70L * index)
            .setDuration(360L)
            .setInterpolator(new OvershootInterpolator(1.1f))
            .start();
    }

    private void animateCardFlip(final View view, int index) {
        view.setCameraDistance(dp(9000));
        view.setRotationY(-90f);
        view.setAlpha(0.45f);
        view.animate()
            .rotationY(0f)
            .alpha(1f)
            .setStartDelay(90L * index)
            .setDuration(420L)
            .setInterpolator(new DecelerateInterpolator())
            .start();
    }

    private void animatePanelEntry(final View view, int index) {
        view.setAlpha(0f);
        view.setTranslationY(dp(14));
        view.animate()
            .alpha(1f)
            .translationY(0f)
            .setStartDelay(45L * index)
            .setDuration(260L)
            .setInterpolator(new DecelerateInterpolator())
            .start();
    }

    private void animateActive(final View view) {
        view.postDelayed(new Runnable() {
            @Override public void run() {
                view.animate().cancel();
                view.setAlpha(1f);
                view.setTranslationY(0f);
                view.setScaleX(0.99f);
                view.setScaleY(0.99f);
                view.animate()
                    .scaleX(1.025f)
                    .scaleY(1.025f)
                    .setDuration(260L)
                    .setInterpolator(new AccelerateDecelerateInterpolator())
                    .withEndAction(new Runnable() {
                        @Override public void run() {
                            view.animate()
                                .scaleX(1f)
                                .scaleY(1f)
                                .setDuration(220L)
                                .setInterpolator(new DecelerateInterpolator())
                                .start();
                        }
                    })
                    .start();
            }
        }, animateCurrentRender ? 360L : 0L);
    }

    private void animateDock() {
        actionDock.setTranslationY(dp(18));
        actionDock.setAlpha(0.82f);
        actionDock.animate()
            .translationY(0f)
            .alpha(1f)
            .setDuration(230L)
            .setInterpolator(new DecelerateInterpolator())
            .start();
    }

    private void feedback(View view) {
        if (vibrationEnabled) {
            view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
        }
        if (soundEnabled && tone != null) {
            tone.startTone(ToneGenerator.TONE_PROP_BEEP, 45);
        }
        view.animate().cancel();
        view.setScaleX(0.96f);
        view.setScaleY(0.96f);
        view.animate().scaleX(1f).scaleY(1f).setDuration(140L).setInterpolator(new DecelerateInterpolator()).start();
    }

    private void resetCardMemory() {
        lastCardCounts.clear();
        lastHiddenRows.clear();
    }

    private void resetAnimationState() {
        resetCardMemory();
        firstRender = true;
        animateNextRender = true;
        tableBusy = false;
        forceHideDealerHole = true;
    }

    private void detachFromParent(View view) {
        if (view != null && view.getParent() instanceof ViewGroup) {
            ((ViewGroup) view.getParent()).removeView(view);
        }
    }

    private boolean compact() {
        DisplayMetrics metrics = getResources().getDisplayMetrics();
        float minDp = Math.min(metrics.widthPixels / metrics.density, metrics.heightPixels / metrics.density);
        return minDp < 600f;
    }

    private int dockWidth() {
        return Math.min(dp(compact() ? 680 : 920), screenWidth() - dp(22));
    }

    private int dockClearance() {
        return dp(compact() ? 118 : 148);
    }

    private int dealerWidth() {
        return dp(compact() ? 196 : 270);
    }

    private int cpuWidth() {
        return dp(compact() ? 150 : 204);
    }

    private int humanWidth() {
        return Math.min(dp(compact() ? 470 : 710), screenWidth() - dp(50));
    }

    private int handWidth(boolean human) {
        return dp(human ? (compact() ? 164 : 224) : (compact() ? 116 : 162));
    }

    private int messageWidth() {
        return Math.min(dp(compact() ? 500 : 730), screenWidth() - dp(96));
    }

    private int cardWidth(boolean small) {
        if (small) return dp(compact() ? 34 : 50);
        return dp(compact() ? 46 : 68);
    }

    private int cardWidthForCount(boolean small, String rowKey, int count) {
        int base = cardWidth(small);
        int min = dp(small ? (compact() ? 22 : 32) : (compact() ? 32 : 46));
        int available = cardStripWidth(rowKey, small);
        if (count <= 0) {
            return base;
        }
        int fitted = available / count - dp(4);
        return Math.min(base, Math.max(min, fitted));
    }

    private int cardRightMarginForCount(int count, int cardWidth, int availableWidth) {
        int side = dp(2);
        int fullWidth = count * (cardWidth + side * 2);
        if (count <= 1 || fullWidth <= availableWidth) {
            return side;
        }
        int overlap = (int) Math.ceil((fullWidth - availableWidth) / (double) (count - 1));
        return side - overlap;
    }

    private int cardStripWidth(String rowKey, boolean small) {
        int padding = dp(compact() ? 16 : 22);
        int width;
        if ("dealer".equals(rowKey)) {
            width = dealerWidth();
        } else if (rowKey != null && rowKey.startsWith("human-")) {
            width = handWidth(true);
        } else {
            width = handWidth(false);
        }
        return Math.max(cardWidth(small), width - padding);
    }

    private FrameLayout.LayoutParams cpuParams(int position) {
        int width = cpuWidth();
        if (position == 0) return anchoredParams(width, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.LEFT, dp(compact() ? 82 : 112), dp(compact() ? 132 : 178), 0, 0);
        if (position == 1) return anchoredParams(width, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.RIGHT, 0, dp(compact() ? 132 : 178), dp(compact() ? 18 : 28), 0);
        if (position == 2) return anchoredParams(width, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.LEFT, dp(compact() ? 46 : 74), 0, 0, dp(compact() ? 126 : 170));
        return anchoredParams(width, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.RIGHT, 0, 0, dp(compact() ? 46 : 74), dp(compact() ? 126 : 170));
    }

    private FrameLayout.LayoutParams absParams(int width, int height, int left, int top) {
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(width, height);
        params.leftMargin = left;
        params.topMargin = top;
        return params;
    }

    private FrameLayout.LayoutParams anchoredParams(int width, int height, int gravity, int left, int top, int right, int bottom) {
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(width, height);
        params.gravity = gravity;
        params.setMargins(left, top, right, bottom);
        return params;
    }

    private LinearLayout.LayoutParams weighted(float weight, int left, int top, int right, int bottom) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, weight);
        params.setMargins(left, top, right, bottom);
        return params;
    }

    private LinearLayout.LayoutParams fullWidth(int left, int top, int right, int bottom) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.setMargins(left, top, right, bottom);
        return params;
    }

    private LinearLayout.LayoutParams miniButtonParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(compact() ? 64 : 82), dp(compact() ? 23 : 28));
        params.setMargins(0, 0, 0, dp(3));
        return params;
    }

    private GradientDrawable gradientRect(int start, int end, int stroke, int radius, int strokeWidth) {
        GradientDrawable drawable = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[] {start, end});
        drawable.setCornerRadius(radius);
        if (stroke != 0 && strokeWidth > 0) {
            drawable.setStroke(strokeWidth, stroke);
        }
        return drawable;
    }

    private GradientDrawable roundRect(int fill, int stroke, int radius, int strokeWidth) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(fill);
        drawable.setCornerRadius(radius);
        if (stroke != 0 && strokeWidth > 0) {
            drawable.setStroke(strokeWidth, stroke);
        }
        return drawable;
    }

    private int shade(int color, float factor) {
        int alpha = (color >>> 24) & 0xFF;
        int red = Math.min(255, Math.max(0, (int) (((color >>> 16) & 0xFF) * factor)));
        int green = Math.min(255, Math.max(0, (int) (((color >>> 8) & 0xFF) * factor)));
        int blue = Math.min(255, Math.max(0, (int) ((color & 0xFF) * factor)));
        return (alpha << 24) | (red << 16) | (green << 8) | blue;
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private int screenWidth() {
        return getResources().getDisplayMetrics().widthPixels;
    }

    private int screenHeight() {
        return getResources().getDisplayMetrics().heightPixels;
    }

    private static final class RoomBackgroundView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();

        RoomBackgroundView(Context context) {
            super(context);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            int w = getWidth();
            int h = getHeight();
            long now = System.currentTimeMillis();
            float sweep = (now % 9000L) / 9000f;

            paint.setStyle(Paint.Style.FILL);
            paint.setShader(new LinearGradient(0, 0, 0, h, 0xFF10172A, 0xFF03050B, Shader.TileMode.CLAMP));
            canvas.drawRect(0, 0, w, h, paint);
            paint.setShader(null);

            paint.setColor(0xFF05070D);
            path.reset();
            path.moveTo(0, h * 0.60f);
            path.lineTo(w, h * 0.50f);
            path.lineTo(w, h);
            path.lineTo(0, h);
            path.close();
            canvas.drawPath(path, paint);

            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(Math.max(1f, w * 0.0014f));
            paint.setColor(0x1932D5FF);
            int panels = 7;
            for (int i = 0; i <= panels; i++) {
                float x = i * w / (float) panels;
                canvas.drawLine(x, 0, x - w * 0.05f, h * 0.58f, paint);
            }

            paint.setStrokeWidth(Math.max(1f, w * 0.001f));
            paint.setColor(0x17FFFFFF);
            for (int i = 0; i < 12; i++) {
                float y = h * 0.62f + i * h * 0.044f;
                canvas.drawLine(-w * 0.08f, y, w * 1.08f, y + i * h * 0.012f, paint);
            }
            for (int i = -4; i <= 4; i++) {
                float x = w * 0.50f + i * w * 0.11f;
                canvas.drawLine(x, h * 0.58f, w * 0.50f + i * w * 0.26f, h, paint);
            }

            paint.setStyle(Paint.Style.FILL);
            paint.setColor(0x2A9C5CFF);
            canvas.drawCircle(w * 0.18f, h * 0.20f, Math.max(80f, w * 0.16f), paint);
            paint.setColor(0x2032D5FF);
            canvas.drawCircle(w * 0.82f, h * 0.16f, Math.max(90f, w * 0.18f), paint);

            float highlightX = -w * 0.30f + sweep * w * 1.6f;
            paint.setShader(new LinearGradient(highlightX - w * 0.16f, 0, highlightX + w * 0.16f, h,
                0x0032D5FF, 0x1832D5FF, Shader.TileMode.CLAMP));
            canvas.drawRect(0, 0, w, h, paint);
            paint.setShader(null);

            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(Math.max(2f, w * 0.004f));
            paint.setColor(0x55FFC85A);
            canvas.drawArc(-w * 0.18f, h * 0.04f, w * 1.18f, h * 0.82f, 205, 130, false, paint);
            paint.setStrokeWidth(Math.max(1f, w * 0.002f));
            paint.setColor(0x4432D5FF);
            canvas.drawArc(w * 0.08f, h * 0.10f, w * 0.92f, h * 0.74f, 205, 130, false, paint);
            postInvalidateOnAnimation();
        }
    }

    private static final class CasinoTableView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        private final RectF table = new RectF();
        private final RectF felt = new RectF();

        CasinoTableView(Context context) {
            super(context);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            int w = getWidth();
            int h = getHeight();
            long now = System.currentTimeMillis();
            float pulse = (float) Math.sin((now % 4200L) / 4200f * Math.PI * 2f) * 0.5f + 0.5f;

            table.set(w * 0.045f, h * 0.145f, w * 0.955f, h * 0.93f);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(0xAA000000);
            canvas.drawRoundRect(table.left + dp(10), table.top + dp(16), table.right + dp(10), table.bottom + dp(18), dp(90), dp(90), paint);
            paint.setShader(new LinearGradient(table.left, table.top, table.right, table.bottom, 0xFF1A1026, 0xFF432719, Shader.TileMode.CLAMP));
            canvas.drawRoundRect(table, dp(96), dp(96), paint);
            paint.setShader(null);

            felt.set(table.left + dp(24), table.top + dp(24), table.right - dp(24), table.bottom - dp(24));
            paint.setShader(new LinearGradient(0, felt.top, 0, felt.bottom, 0xFF0D4E58, 0xFF071D2A, Shader.TileMode.CLAMP));
            canvas.drawRoundRect(felt, dp(78), dp(78), paint);
            paint.setShader(null);

            drawTableTexture(canvas, w, h);
            drawDealerBand(canvas, w, h, pulse);
            drawSeatGrid(canvas, w, h);
            drawCenterMark(canvas, w, h, pulse);
            drawProps(canvas, w, h, now);

            float sweepX = -w * 0.20f + (now % 5600L) / 5600f * w * 1.4f;
            paint.setStyle(Paint.Style.FILL);
            paint.setShader(new LinearGradient(sweepX - dp(90), 0, sweepX + dp(90), h, 0x00FFFFFF, 0x1432D5FF, Shader.TileMode.CLAMP));
            canvas.drawRoundRect(felt, dp(78), dp(78), paint);
            paint.setShader(null);
            postInvalidateOnAnimation();
        }

        private void drawTableTexture(Canvas canvas, int w, int h) {
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(5));
            paint.setColor(0xD8FFC85A);
            canvas.drawRoundRect(felt, dp(78), dp(78), paint);
            paint.setStrokeWidth(dp(2));
            paint.setColor(0x9932D5FF);
            canvas.drawRoundRect(felt.left + dp(10), felt.top + dp(10), felt.right - dp(10), felt.bottom - dp(10), dp(68), dp(68), paint);
            paint.setStrokeWidth(dp(1));
            paint.setColor(0x12000000);
            int step = Math.max(dp(18), w / 34);
            for (int x = (int) felt.left - w; x < felt.right + w; x += step) {
                canvas.drawLine(x, felt.top, x + h, felt.bottom, paint);
                canvas.drawLine(x, felt.bottom, x + h, felt.top, paint);
            }
        }

        private void drawDealerBand(Canvas canvas, int w, int h, float pulse) {
            RectF arc = new RectF(felt.left + dp(70), felt.top + dp(18), felt.right - dp(70), felt.bottom + dp(176));
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(2));
            paint.setColor(0x8832D5FF + ((int) (pulse * 42f) << 24));
            canvas.drawArc(arc, 205, 130, false, paint);
            paint.setStrokeWidth(dp(1));
            paint.setColor(0x99FFC85A);
            canvas.drawArc(new RectF(arc.left + dp(28), arc.top + dp(24), arc.right - dp(28), arc.bottom - dp(12)), 208, 124, false, paint);

            paint.setStyle(Paint.Style.FILL);
            paint.setTextAlign(Paint.Align.CENTER);
            paint.setFakeBoldText(true);
            paint.setColor(0xD9F8FBFF);
            paint.setTextSize(Math.max(dp(12), w * 0.017f));
            path.reset();
            path.addArc(arc, 211, 118);
            canvas.drawTextOnPath("BLACKJACK PAYS 3 TO 2", path, 0, -dp(12), paint);
            paint.setColor(0xA8B8C5D6);
            paint.setTextSize(Math.max(dp(9), w * 0.013f));
            path.reset();
            path.addArc(new RectF(arc.left + dp(52), arc.top + dp(42), arc.right - dp(52), arc.bottom - dp(26)), 213, 114);
            canvas.drawTextOnPath("Dealer stands on soft 17", path, 0, -dp(6), paint);
            paint.setFakeBoldText(false);
        }

        private void drawSeatGrid(Canvas canvas, int w, int h) {
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(2));
            paint.setColor(0xA7F8FBFF);
            drawSeat(canvas, w * 0.18f, h * 0.65f, w * 0.13f, h * 0.145f, -10f, "GUEST");
            drawSeat(canvas, w * 0.34f, h * 0.78f, w * 0.14f, h * 0.15f, -4f, "HAND");
            drawSeat(canvas, w * 0.50f, h * 0.82f, w * 0.15f, h * 0.15f, 0f, "PLAYER");
            drawSeat(canvas, w * 0.66f, h * 0.78f, w * 0.14f, h * 0.15f, 4f, "HAND");
            drawSeat(canvas, w * 0.82f, h * 0.65f, w * 0.13f, h * 0.145f, 10f, "GUEST");
        }

        private void drawSeat(Canvas canvas, float cx, float cy, float bw, float bh, float rotation, String label) {
            canvas.save();
            canvas.rotate(rotation, cx, cy);
            RectF box = new RectF(cx - bw / 2f, cy - bh / 2f, cx + bw / 2f, cy + bh / 2f);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(0x17000000);
            canvas.drawRoundRect(box.left + dp(4), box.top + dp(5), box.right + dp(4), box.bottom + dp(5), dp(12), dp(12), paint);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(2));
            paint.setColor(0x9FF8FBFF);
            canvas.drawRoundRect(box, dp(12), dp(12), paint);
            paint.setStrokeWidth(dp(1));
            paint.setColor(0x6632D5FF);
            canvas.drawRoundRect(box.left + dp(6), box.top + dp(6), box.right - dp(6), box.bottom - dp(6), dp(8), dp(8), paint);
            paint.setStyle(Paint.Style.FILL);
            paint.setTextAlign(Paint.Align.CENTER);
            paint.setFakeBoldText(true);
            paint.setTextSize(Math.max(dp(8), bw * 0.10f));
            paint.setColor(0x72F8FBFF);
            canvas.drawText(label, cx, cy + dp(4), paint);
            paint.setFakeBoldText(false);
            canvas.restore();
        }

        private void drawCenterMark(Canvas canvas, int w, int h, float pulse) {
            paint.setStyle(Paint.Style.FILL);
            paint.setTextAlign(Paint.Align.CENTER);
            paint.setFakeBoldText(true);
            paint.setColor(0x3032D5FF + ((int) (pulse * 28f) << 24));
            paint.setTextSize(Math.max(dp(32), w * 0.052f));
            canvas.drawText("21", w * 0.50f, h * 0.49f, paint);
            paint.setColor(0xA8FFC85A);
            paint.setTextSize(Math.max(dp(10), w * 0.014f));
            canvas.drawText("ROYAL TABLE", w * 0.50f, h * 0.535f, paint);
            paint.setFakeBoldText(false);
        }

        private void drawProps(Canvas canvas, int w, int h, long now) {
            drawShoe(canvas, w, h);
            drawDiscardPile(canvas, w, h);
            float bob = (float) Math.sin((now % 1800L) / 1800f * Math.PI * 2f) * dp(1);
            drawChipStack(canvas, w * 0.30f, h * 0.18f + bob, new int[] {0xFF0EA5E9, 0xFF7C3AED, 0xFFFFC85A, 0xFF0F172A});
            drawChipStack(canvas, w * 0.14f, h * 0.83f - bob, new int[] {0xFFE5485E, 0xFFFFC85A, 0xFF2563EB, 0xFF0F172A});
            drawChipStack(canvas, w * 0.50f, h * 0.875f + bob, new int[] {0xFF7C3AED, 0xFF0F172A, 0xFFFFC85A, 0xFF14B878});
            drawChipStack(canvas, w * 0.86f, h * 0.82f - bob, new int[] {0xFFFFC85A, 0xFF32D5FF, 0xFFE5485E, 0xFF0F172A});
        }

        private void drawShoe(Canvas canvas, int w, int h) {
            canvas.save();
            canvas.rotate(-12f, w * 0.22f, h * 0.12f);
            RectF shoe = new RectF(w * 0.15f, h * 0.04f, w * 0.29f, h * 0.155f);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(0x88000000);
            canvas.drawRoundRect(shoe.left + dp(7), shoe.top + dp(9), shoe.right + dp(7), shoe.bottom + dp(9), dp(12), dp(12), paint);
            paint.setShader(new LinearGradient(shoe.left, shoe.top, shoe.right, shoe.bottom, 0xFF1E293B, 0xFF020617, Shader.TileMode.CLAMP));
            canvas.drawRoundRect(shoe, dp(10), dp(10), paint);
            paint.setShader(null);
            paint.setColor(0xFFEFF6FF);
            canvas.drawRoundRect(shoe.left + dp(10), shoe.top + dp(9), shoe.right - dp(24), shoe.bottom - dp(9), dp(5), dp(5), paint);
            paint.setColor(0xFF32D5FF);
            canvas.drawRoundRect(shoe.right - dp(46), shoe.top + dp(14), shoe.right - dp(8), shoe.bottom - dp(14), dp(5), dp(5), paint);
            canvas.restore();
        }

        private void drawDiscardPile(Canvas canvas, int w, int h) {
            canvas.save();
            canvas.rotate(10f, w * 0.80f, h * 0.13f);
            for (int i = 0; i < 4; i++) {
                RectF card = new RectF(w * 0.765f + i * dp(10), h * 0.055f + i * dp(4), w * 0.835f + i * dp(10), h * 0.145f + i * dp(4));
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(0x66000000);
                canvas.drawRoundRect(card.left + dp(3), card.top + dp(4), card.right + dp(3), card.bottom + dp(4), dp(4), dp(4), paint);
                paint.setColor(i % 2 == 0 ? 0xFF0EA5E9 : 0xFFE5485E);
                canvas.drawRoundRect(card, dp(4), dp(4), paint);
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(dp(1));
                paint.setColor(0xCCF8FBFF);
                canvas.drawRoundRect(card.left + dp(4), card.top + dp(4), card.right - dp(4), card.bottom - dp(4), dp(3), dp(3), paint);
            }
            canvas.restore();
        }

        private void drawChipStack(Canvas canvas, float x, float y, int[] colors) {
            for (int i = 0; i < colors.length; i++) {
                drawChip(canvas, x + (i % 2) * dp(22), y - i * dp(6), colors[i], i % 3 == 0 ? "100" : i % 3 == 1 ? "50" : "200");
            }
        }

        private void drawChip(Canvas canvas, float cx, float cy, int color, String label) {
            float r = dp(14);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(0x66000000);
            canvas.drawCircle(cx + dp(3), cy + dp(4), r, paint);
            paint.setShader(new LinearGradient(cx - r, cy - r, cx + r, cy + r, 0xFFFEF3C7, color, Shader.TileMode.CLAMP));
            canvas.drawCircle(cx, cy, r, paint);
            paint.setShader(null);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(2));
            paint.setColor(0xFFFFFFFF);
            canvas.drawCircle(cx, cy, r * 0.72f, paint);
            paint.setStrokeWidth(dp(1));
            paint.setColor(0xAA020617);
            canvas.drawCircle(cx, cy, r * 0.42f, paint);
            paint.setStyle(Paint.Style.FILL);
            paint.setTextAlign(Paint.Align.CENTER);
            paint.setFakeBoldText(true);
            paint.setTextSize(dp(7));
            paint.setColor(0xEEFFFFFF);
            canvas.drawText(label, cx, cy + dp(3), paint);
            paint.setFakeBoldText(false);
        }

        private int dp(int value) {
            return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
        }
    }
}
