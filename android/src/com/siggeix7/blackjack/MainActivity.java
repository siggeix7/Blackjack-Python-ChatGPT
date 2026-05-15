package com.siggeix7.blackjack;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Color;
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
    private static final int FELT_DARK = 0xFF06241B;
    private static final int FELT = 0xFF08735F;
    private static final int PANEL = 0xE7071B14;
    private static final int PANEL_SOFT = 0x8C061B14;
    private static final int GOLD = 0xFFD6AF4D;
    private static final int GOLD_DARK = 0xFFA77A30;
    private static final int BLUE = 0xFF1F5D79;
    private static final int GREEN = 0xFF207A58;
    private static final int RED = 0xFFC52845;
    private static final int TEXT = 0xFFF8F3E4;
    private static final int MUTED = 0xFFD0D9D0;

    private SharedPreferences prefs;
    private BlackjackGame game;
    private Handler handler;
    private FrameLayout root;
    private FrameLayout tableLayer;
    private LinearLayout actionDock;
    private TextView logTicker;

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
        String savedName = prefs.getString("name", null);
        game = new BlackjackGame(savedName == null ? "Giocatore" : savedName);
        if (savedName != null) {
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
        actionDock.setPadding(dp(10), dp(8), dp(10), dp(8));
        actionDock.setBackground(roundRect(PANEL, GOLD_DARK, dp(18), dp(1)));
        root.addView(actionDock, dockParams());

        logTicker = new TextView(this);
        logTicker.setTextColor(MUTED);
        logTicker.setTextSize(compact() ? 10 : 12);
        logTicker.setSingleLine(true);
        logTicker.setEllipsize(TextUtils.TruncateAt.START);
        logTicker.setGravity(Gravity.CENTER);
        logTicker.setPadding(dp(10), dp(4), dp(10), dp(4));
        logTicker.setBackground(roundRect(0x80030F0B, 0x335A765F, dp(10), dp(1)));

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
        TextView title = new TextView(this);
        title.setText("BLACKJACK ROYAL");
        title.setTextColor(TEXT);
        title.setTextSize(compact() ? 18 : 28);
        title.setTypeface(Typeface.create("serif", Typeface.BOLD));
        title.setLetterSpacing(0.06f);
        title.setShadowLayer(dp(4), 0, dp(2), 0xAA000000);
        title.setGravity(Gravity.CENTER);
        tableLayer.addView(title, absParams(dp(compact() ? 230 : 420), ViewGroup.LayoutParams.WRAP_CONTENT,
            dp(compact() ? 10 : 22), dp(compact() ? 6 : 12)));
    }

    private void addTopHud() {
        LinearLayout hud = new LinearLayout(this);
        hud.setOrientation(LinearLayout.HORIZONTAL);
        hud.setGravity(Gravity.CENTER);
        hud.addView(statusChip("Saldo", BlackjackGame.money(game.human.balance)), weighted(1f, dp(3), 0, dp(3), 0));
        hud.addView(statusChip("Puntata", BlackjackGame.money(game.currentBet)), weighted(1f, dp(3), 0, dp(3), 0));
        hud.addView(statusChip("Banco", BlackjackGame.money(game.dealerBankroll)), weighted(1f, dp(3), 0, dp(3), 0));
        tableLayer.addView(hud, anchoredParams(dp(compact() ? 310 : 470), ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.TOP | Gravity.RIGHT, 0, dp(compact() ? 8 : 14), dp(compact() ? 10 : 18), 0));
    }

    private TextView statusChip(String label, String value) {
        TextView chip = new TextView(this);
        chip.setText(label + "\n" + value);
        chip.setTextColor(TEXT);
        chip.setTextSize(compact() ? 10 : 12);
        chip.setTypeface(Typeface.DEFAULT_BOLD);
        chip.setGravity(Gravity.CENTER);
        chip.setPadding(dp(8), dp(6), dp(8), dp(6));
        chip.setBackground(roundRect(0xD70A3A2B, GOLD_DARK, dp(16), dp(1)));
        return chip;
    }

    private void addMiniMenu() {
        LinearLayout menu = new LinearLayout(this);
        menu.setOrientation(LinearLayout.HORIZONTAL);
        menu.setGravity(Gravity.CENTER);
        menu.addView(tinyButton("Stats", 0xCC24483A, new View.OnClickListener() {
            @Override public void onClick(View v) { feedback(v); showStatsDialog(); }
        }), miniButtonParams());
        menu.addView(tinyButton("Hall", 0xCC24483A, new View.OnClickListener() {
            @Override public void onClick(View v) { feedback(v); showHallDialog(); }
        }), miniButtonParams());
        menu.addView(tinyButton("Reset", 0xCC653323, new View.OnClickListener() {
            @Override public void onClick(View v) { feedback(v); confirmNewGame(); }
        }), miniButtonParams());
        tableLayer.addView(menu, absParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            dp(compact() ? 12 : 24), dp(compact() ? 52 : 70)));
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
        seat.addView(seatTitle("Mazziere - " + state));
        addCardStrip(seat, game.dealerHand, !reveal && game.dealerHand.size() > 1, true, "dealer");
        tableLayer.addView(seat, anchoredParams(dealerWidth(), ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.TOP | Gravity.CENTER_HORIZONTAL, 0, dp(compact() ? 52 : 76), 0, 0));
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
        msg.setPadding(dp(12), dp(7), dp(12), dp(7));
        msg.setBackground(roundRect(0x9E061711, GOLD_DARK, dp(14), dp(1)));
        tableLayer.addView(msg, anchoredParams(messageWidth(), ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.CENTER, 0, 0, 0, dp(compact() ? 22 : 42)));
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
        String role = human ? "Tu" : shortDifficulty(player.difficulty);
        seat.addView(seatTitle(player.name + " - " + role + " - " + BlackjackGame.money(player.balance)));

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
            handBox.setPadding(dp(4), 0, dp(4), dp(3));
            handBox.setBackground(roundRect(0x22000000, 0x99F4F1E6, dp(7), dp(1)));
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
        label.setText("M" + (handIndex + 1) + turn + "  " + state + "  " + BlackjackGame.money(player.bets.get(handIndex).intValue()));
        label.setTextColor(MUTED);
        label.setTextSize(compact() ? 9 : 11);
        label.setGravity(Gravity.CENTER);
        return label;
    }

    private LinearLayout seatPanel(boolean active, boolean dealer) {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setGravity(Gravity.CENTER);
        panel.setPadding(dp(compact() ? 6 : 8), dp(compact() ? 5 : 7), dp(compact() ? 6 : 8), dp(compact() ? 5 : 7));
        int fill = dealer ? 0x98041611 : active ? 0xB91F7A52 : 0x4A041611;
        int stroke = active ? GOLD : dealer ? 0x8892A494 : 0x77FFFFFF;
        panel.setBackground(roundRect(fill, stroke, dp(15), dp(1)));
        panel.setElevation(active ? dp(10) : dp(4));
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
        title.setPadding(0, 0, 0, dp(4));
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
        row.addView(dockText("Puntata: " + BlackjackGame.money(game.currentBet)), weighted(1.35f, dp(2), 0, dp(2), dp(4)));
        row.addView(actionButton("-10", 0xFF31473C, new View.OnClickListener() {
            @Override public void onClick(View v) { game.changeBet(-10); feedback(v); render(); }
        }), weighted(0.8f, dp(2), 0, dp(2), dp(4)));
        row.addView(actionButton("+10", 0xFF31473C, new View.OnClickListener() {
            @Override public void onClick(View v) { game.changeBet(10); feedback(v); render(); }
        }), weighted(0.8f, dp(2), 0, dp(2), dp(4)));
        row.addView(actionButton("+25", 0xFF31473C, new View.OnClickListener() {
            @Override public void onClick(View v) { game.changeBet(25); feedback(v); render(); }
        }), weighted(0.8f, dp(2), 0, dp(2), dp(4)));
        row.addView(actionButton("Max", 0xFF31473C, new View.OnClickListener() {
            @Override public void onClick(View v) { game.setMaxBet(); feedback(v); render(); }
        }), weighted(0.8f, dp(2), 0, dp(2), dp(4)));
        Button deal = actionButton(game.phase == BlackjackGame.Phase.ROUND_OVER ? "Nuova mano" : "Distribuisci", GOLD_DARK, new View.OnClickListener() {
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
        row.addView(deal, weighted(1.65f, dp(2), 0, dp(2), dp(4)));
        actionDock.addView(row, fullWidth(0, 0, 0, 0));
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
        row.addView(actionButton("No", 0xFF31473C, new View.OnClickListener() {
            @Override public void onClick(View v) { feedback(v); game.answerInsurance(false); afterGameAction(); }
        }), weighted(0.75f, dp(2), 0, dp(2), 0));
        actionDock.addView(row, fullWidth(0, 0, 0, 0));
    }

    private void renderPlayerActions() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER);
        Button hit = actionButton("Carta", GREEN, new View.OnClickListener() {
            @Override public void onClick(View v) { feedback(v); game.hit(); afterGameAction(); }
        });
        hit.setEnabled(game.canHit());
        row.addView(hit, weighted(1f, dp(2), 0, dp(2), dp(4)));
        Button stand = actionButton("Sto", 0xFF31473C, new View.OnClickListener() {
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
        view.setGravity(Gravity.CENTER);
        view.setPadding(dp(8), dp(5), dp(8), dp(5));
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
        button.setTextColor(Color.WHITE);
        button.setTextSize(compact() ? 10 : 12);
        button.setAllCaps(false);
        button.setMinHeight(dp(compact() ? 34 : 40));
        button.setGravity(Gravity.CENTER);
        button.setPadding(dp(6), 0, dp(6), 0);
        button.setBackground(roundRect(color, 0x55FFFFFF, dp(13), dp(1)));
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
            empty.setText("Carte non distribuite");
            empty.setTextColor(MUTED);
            empty.setTextSize(compact() ? 9 : 11);
            empty.setGravity(Gravity.CENTER);
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
        }, 650L);
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
        }, dealStep > secondDealerStep ? 900L : 520L);
    }

    private void startCpuSequence() {
        tableBusy = true;
        forceHideDealerHole = true;
        cinematicMessage = "Gli altri giocatori studiano il banco...";
        animateNextRender = true;
        render();
        handler.postDelayed(new Runnable() {
            @Override public void run() { runCpuStep(); }
        }, 850L);
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
            }, Math.max(650, action.delayMs));
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
        }, 1200L);
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
        }, game.dealerHasBlackjack() ? 1400L : 950L);
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
            }, 1150L);
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
        stats.hands = prefs.getInt("stats_hands", 0);
        stats.wins = prefs.getInt("stats_wins", 0);
        stats.losses = prefs.getInt("stats_losses", 0);
        stats.pushes = prefs.getInt("stats_pushes", 0);
        stats.busts = prefs.getInt("stats_busts", 0);
        stats.blackjacks = prefs.getInt("stats_blackjacks", 0);
        stats.doubles = prefs.getInt("stats_doubles", 0);
        stats.splits = prefs.getInt("stats_splits", 0);
        stats.surrenders = prefs.getInt("stats_surrenders", 0);
        stats.insuranceWon = prefs.getInt("stats_insurance", 0);
        stats.net = prefs.getInt("stats_net", 0);
        game.restoreProgress(
            prefs.getInt("balance", BlackjackGame.START_BALANCE),
            prefs.getInt("dealer_bank", BlackjackGame.START_DEALER_BANK),
            prefs.getInt("current_bet", BlackjackGame.MIN_BET),
            stats);
    }

    private void persistProgress() {
        Player.Stats stats = game.human.stats;
        prefs.edit()
            .putString("name", game.human.name)
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
            .putInt("stats_net", stats.net)
            .apply();
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
        input.setBackground(roundRect(0xCC10291F, GOLD_DARK, dp(10), dp(1)));
        box.addView(input, fullWidth(0, 0, 0, dp(12)));
        box.addView(actionButton("Siediti al tavolo", GOLD_DARK, new View.OnClickListener() {
            @Override public void onClick(View v) {
                feedback(v);
                game.newSession(input.getText().toString());
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
        box.setBackground(roundRect(0xF20A1D16, GOLD_DARK, dp(20), dp(1)));
        return box;
    }

    private TextView dialogTitle(String text) {
        TextView title = new TextView(this);
        title.setText(text);
        title.setTextColor(TEXT);
        title.setTextSize(22);
        title.setTypeface(Typeface.create("serif", Typeface.BOLD));
        title.setGravity(Gravity.CENTER);
        title.setPadding(0, 0, 0, dp(10));
        return title;
    }

    private TextView dialogMessage(String text) {
        TextView message = new TextView(this);
        message.setText(text);
        message.setTextColor(MUTED);
        message.setTextSize(15);
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
        view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
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
        return Math.min(dp(compact() ? 650 : 880), screenWidth() - dp(24));
    }

    private int dockClearance() {
        return dp(compact() ? 116 : 146);
    }

    private int dealerWidth() {
        return dp(compact() ? 172 : 232);
    }

    private int cpuWidth() {
        return dp(compact() ? 138 : 190);
    }

    private int humanWidth() {
        return Math.min(dp(compact() ? 420 : 640), screenWidth() - dp(40));
    }

    private int handWidth(boolean human) {
        return dp(human ? (compact() ? 150 : 210) : (compact() ? 108 : 154));
    }

    private int messageWidth() {
        return Math.min(dp(compact() ? 460 : 680), screenWidth() - dp(80));
    }

    private int cardWidth(boolean small) {
        if (small) return dp(compact() ? 32 : 48);
        return dp(compact() ? 44 : 64);
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
        if (position == 0) return anchoredParams(width, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.LEFT, dp(18), dp(compact() ? 128 : 166), 0, 0);
        if (position == 1) return anchoredParams(width, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.RIGHT, 0, dp(compact() ? 128 : 166), dp(18), 0);
        if (position == 2) return anchoredParams(width, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.LEFT, dp(18), 0, 0, dp(compact() ? 118 : 162));
        return anchoredParams(width, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.RIGHT, 0, 0, dp(18), dp(compact() ? 118 : 162));
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
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(compact() ? 58 : 74), dp(compact() ? 30 : 34));
        params.setMargins(0, 0, dp(6), 0);
        return params;
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

        RoomBackgroundView(Context context) {
            super(context);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            int w = getWidth();
            int h = getHeight();
            long now = System.currentTimeMillis();
            float sweep = (now % 7000L) / 7000f;
            paint.setShader(new LinearGradient(0, 0, 0, h, 0xFF151C19, 0xFF08251C, Shader.TileMode.CLAMP));
            canvas.drawRect(0, 0, w, h, paint);
            paint.setShader(null);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(1f);
            paint.setColor(0x13000000);
            int step = Math.max(20, w / 30);
            for (int x = -w; x < w * 2; x += step) {
                canvas.drawLine(x, 0, x + h, h, paint);
                canvas.drawLine(x, h, x + h, 0, paint);
            }
            float highlightX = -w * 0.35f + sweep * w * 1.7f;
            paint.setStyle(Paint.Style.FILL);
            paint.setShader(new LinearGradient(highlightX - w * 0.12f, 0, highlightX + w * 0.12f, h,
                0x00FFFFFF, 0x16D9B861, Shader.TileMode.CLAMP));
            canvas.drawRect(0, 0, w, h, paint);
            paint.setShader(null);
            paint.setColor(0x18FFFFFF);
            canvas.drawCircle(w * 0.14f, h * 0.08f, Math.max(40f, w * 0.10f), paint);
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
            float glow = (float) Math.sin((now % 3600L) / 3600f * Math.PI * 2f) * 0.5f + 0.5f;

            table.set(-w * 0.05f, dp(22), w * 1.05f, h + dp(42));
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(0x88000000);
            canvas.drawOval(table.left + dp(8), table.top + dp(18), table.right + dp(8), table.bottom + dp(18), paint);
            paint.setShader(new LinearGradient(0, table.top, 0, table.bottom, 0xFF3D2110, 0xFF936028, Shader.TileMode.CLAMP));
            canvas.drawOval(table, paint);
            paint.setShader(null);

            felt.set(dp(24), dp(44), w - dp(24), h - dp(28));
            paint.setShader(new LinearGradient(0, felt.top, 0, felt.bottom, 0xFF0B7A67, 0xFF053C2F, Shader.TileMode.CLAMP));
            canvas.drawOval(felt, paint);
            paint.setShader(null);

            drawTexture(canvas, w, h);
            drawRules(canvas, w, h, glow);
            drawBettingLayout(canvas, w, h);
            drawProps(canvas, w, h, now);

            float sweepX = -w * 0.20f + (now % 5200L) / 5200f * w * 1.4f;
            paint.setShader(new LinearGradient(sweepX - dp(80), 0, sweepX + dp(80), h, 0x00FFFFFF, 0x16FFFFFF, Shader.TileMode.CLAMP));
            canvas.drawOval(felt, paint);
            paint.setShader(null);
            postInvalidateOnAnimation();
        }

        private void drawTexture(Canvas canvas, int w, int h) {
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(4));
            paint.setColor(0xE1C79A43);
            canvas.drawOval(felt, paint);
            paint.setStrokeWidth(dp(2));
            paint.setColor(0xCAF3F0E4);
            RectF ruleArc = new RectF(dp(72), dp(86), w - dp(72), h + dp(244));
            canvas.drawArc(ruleArc, 197, 146, false, paint);
            paint.setStrokeWidth(dp(1));
            paint.setColor(0x60F9E3A0);
            canvas.drawOval(dp(48), dp(62), w - dp(48), h - dp(54), paint);
            paint.setColor(0x11000000);
            int step = Math.max(dp(22), w / 28);
            for (int x = -w; x < w * 2; x += step) {
                canvas.drawLine(x, 0, x + h, h, paint);
                canvas.drawLine(x, h, x + h, 0, paint);
            }
        }

        private void drawRules(Canvas canvas, int w, int h, float glow) {
            RectF topArc = new RectF(dp(70), dp(88), w - dp(70), h + dp(240));
            paint.setStyle(Paint.Style.FILL);
            paint.setTextAlign(Paint.Align.CENTER);
            paint.setFakeBoldText(true);
            paint.setColor(0xD4D7D061);
            paint.setTextSize(Math.max(dp(15), w * 0.024f));
            path.reset();
            path.addArc(topArc, 208, 124);
            canvas.drawTextOnPath("BLACK JACK PAYS 3 TO 2", path, 0, -dp(18), paint);

            paint.setColor(0xD8E8E072);
            paint.setTextSize(Math.max(dp(12), w * 0.018f));
            path.reset();
            path.addArc(new RectF(dp(118), dp(122), w - dp(118), h + dp(214)), 208, 124);
            canvas.drawTextOnPath("Dealer must draw to 16, and stand on all 17's", path, 0, -dp(8), paint);

            paint.setColor(0x642C1730 + ((int) (glow * 35) << 24));
            paint.setTextSize(Math.max(dp(22), w * 0.036f));
            canvas.drawText("INSURANCE", w / 2f, h * 0.38f, paint);
            paint.setFakeBoldText(false);
        }

        private void drawBettingLayout(Canvas canvas, int w, int h) {
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(2));
            paint.setColor(0xC9F4F1E6);
            drawBox(canvas, w * 0.17f, h * 0.70f, w * 0.12f, h * 0.16f, -10f);
            drawBox(canvas, w * 0.36f, h * 0.83f, w * 0.14f, h * 0.16f, -4f);
            drawBox(canvas, w * 0.50f, h * 0.87f, w * 0.14f, h * 0.16f, 0f);
            drawBox(canvas, w * 0.64f, h * 0.83f, w * 0.14f, h * 0.16f, 4f);
            drawBox(canvas, w * 0.83f, h * 0.70f, w * 0.12f, h * 0.16f, 10f);
        }

        private void drawBox(Canvas canvas, float cx, float cy, float bw, float bh, float rotation) {
            canvas.save();
            canvas.rotate(rotation, cx, cy);
            RectF box = new RectF(cx - bw / 2f, cy - bh / 2f, cx + bw / 2f, cy + bh / 2f);
            canvas.drawRoundRect(box, dp(4), dp(4), paint);
            canvas.restore();
        }

        private void drawProps(Canvas canvas, int w, int h, long now) {
            drawShoe(canvas, w, h);
            drawDiscardPile(canvas, w, h);
            float bob = (float) Math.sin((now % 1800L) / 1800f * Math.PI * 2f) * dp(1);
            drawChipStack(canvas, w * 0.30f, h * 0.15f + bob, new int[] {0xFF202537, 0xFF7C2AC9, 0xFF35A8D9, 0xFFF4D232});
            drawChipStack(canvas, w * 0.13f, h * 0.86f - bob, new int[] {0xFFE08A25, 0xFFF2DC39, 0xFF6C35B8, 0xFF25293A});
            drawChipStack(canvas, w * 0.50f, h * 0.89f + bob, new int[] {0xFF6C35B8, 0xFF202537, 0xFFF2DC39, 0xFFE08A25});
            drawChipStack(canvas, w * 0.86f, h * 0.84f - bob, new int[] {0xFFF2DC39, 0xFF35A8D9, 0xFFE08A25, 0xFF6C35B8});
        }

        private void drawShoe(Canvas canvas, int w, int h) {
            canvas.save();
            canvas.rotate(-18f, w * 0.22f, h * 0.10f);
            RectF shoe = new RectF(w * 0.15f, h * 0.00f, w * 0.30f, h * 0.13f);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(0x88000000);
            canvas.drawRoundRect(shoe.left + dp(8), shoe.top + dp(10), shoe.right + dp(8), shoe.bottom + dp(10), dp(10), dp(10), paint);
            paint.setShader(new LinearGradient(shoe.left, shoe.top, shoe.right, shoe.bottom, 0xFFE6E2F1, 0xFF746F86, Shader.TileMode.CLAMP));
            canvas.drawRoundRect(shoe, dp(10), dp(10), paint);
            paint.setShader(null);
            paint.setColor(0xFFF6F0F6);
            canvas.drawRoundRect(shoe.left + dp(10), shoe.top + dp(8), shoe.right - dp(20), shoe.bottom - dp(8), dp(5), dp(5), paint);
            paint.setColor(0xFFD01438);
            canvas.drawRoundRect(shoe.right - dp(44), shoe.top + dp(13), shoe.right - dp(8), shoe.bottom - dp(13), dp(4), dp(4), paint);
            canvas.restore();
        }

        private void drawDiscardPile(Canvas canvas, int w, int h) {
            canvas.save();
            canvas.rotate(8f, w * 0.80f, h * 0.10f);
            for (int i = 0; i < 4; i++) {
                RectF card = new RectF(w * 0.77f + i * dp(10), h * 0.02f + i * dp(4), w * 0.84f + i * dp(10), h * 0.11f + i * dp(4));
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(0x66000000);
                canvas.drawRoundRect(card.left + dp(3), card.top + dp(4), card.right + dp(3), card.bottom + dp(4), dp(4), dp(4), paint);
                paint.setColor(0xFFD6113D);
                canvas.drawRoundRect(card, dp(4), dp(4), paint);
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(dp(1));
                paint.setColor(0xFFFFB5C3);
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
            paint.setShader(new LinearGradient(cx - r, cy - r, cx + r, cy + r, 0xFFFFFFFF, color, Shader.TileMode.CLAMP));
            canvas.drawCircle(cx, cy, r, paint);
            paint.setShader(null);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(2));
            paint.setColor(0xFFFFFFFF);
            canvas.drawCircle(cx, cy, r * 0.72f, paint);
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
