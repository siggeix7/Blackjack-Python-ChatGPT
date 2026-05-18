package com.siggeix7.blackjack;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.Build;
import android.os.Bundle;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;

import java.util.ArrayList;

public final class MainActivity extends Activity {
    private PlatformView gameView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestLandscape();
        getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        enterFullscreen();
        gameView = new PlatformView(this);
        setContentView(gameView);
    }

    @Override
    protected void onResume() {
        super.onResume();
        enterFullscreen();
        if (gameView != null) {
            gameView.resume();
        }
    }

    @Override
    protected void onPause() {
        if (gameView != null) {
            gameView.pauseForSystem();
        }
        super.onPause();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            enterFullscreen();
        }
    }

    private void requestLandscape() {
        try {
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
        } catch (RuntimeException ignored) {
        }
    }

    private void enterFullscreen() {
        applyFullscreen(getWindow());
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
                window.getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
            }
        } catch (RuntimeException ignored) {
        }
    }

    private static final class PlatformView extends View implements Runnable {
        private static final String PREFS = "velvet_run_64";
        private static final int MENU = 0;
        private static final int PLAYING = 1;
        private static final int PAUSED = 2;
        private static final int LEVEL_CLEAR = 3;
        private static final int GAME_OVER = 4;
        private static final int COMPLETED = 5;

        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        private final RectF rect = new RectF();
        private final SharedPreferences prefs;
        private final ArrayList<Level> levels = new ArrayList<Level>();

        private int state = MENU;
        private int levelIndex;
        private int unlockedLevel;
        private int coins;
        private int gems;
        private int lives = 3;
        private int score;
        private float bestTime;
        private float runTime;
        private float playerX;
        private float playerY;
        private float playerVx;
        private float playerVy;
        private float checkpointX;
        private float checkpointY;
        private boolean onGround;
        private boolean jumpWasDown;
        private boolean facingRight = true;
        private boolean running;
        private boolean leftPressed;
        private boolean rightPressed;
        private boolean jumpPressed;
        private float cameraX;
        private long lastFrameNanos;
        private String toast = "";
        private float toastTimer;

        PlatformView(Context context) {
            super(context);
            setFocusable(true);
            setFocusableInTouchMode(true);
            prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            buildLevels();
            loadProgress();
            resetLevel(levelIndex, false);
        }

        void resume() {
            running = true;
            lastFrameNanos = System.nanoTime();
            removeCallbacks(this);
            postOnAnimation(this);
        }

        void pauseForSystem() {
            saveProgress();
            running = false;
            removeCallbacks(this);
        }

        @Override
        public void run() {
            if (!running) {
                return;
            }
            long now = System.nanoTime();
            float dt = Math.min(0.033f, (now - lastFrameNanos) / 1000000000f);
            lastFrameNanos = now;
            update(dt);
            invalidate();
            postOnAnimation(this);
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_POINTER_DOWN) {
                handleTap(event.getX(event.getActionIndex()), event.getY(event.getActionIndex()));
            }
            updateControls(event);
            return true;
        }

        private void handleTap(float x, float y) {
            float w = getWidth();
            float h = getHeight();
            if (state == MENU) {
                if (y > h * 0.42f && y < h * 0.58f) {
                    state = PLAYING;
                    showToast("Level " + (levelIndex + 1) + ": " + currentLevel().name);
                    performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                } else if (y > h * 0.61f && y < h * 0.75f) {
                    newGame();
                    state = PLAYING;
                }
                return;
            }
            if (state == PAUSED) {
                if (y < h * 0.55f) {
                    state = PLAYING;
                } else {
                    state = MENU;
                    saveProgress();
                }
                return;
            }
            if (state == LEVEL_CLEAR) {
                nextLevel();
                return;
            }
            if (state == GAME_OVER || state == COMPLETED) {
                newGame();
                state = PLAYING;
                return;
            }
            if (state == PLAYING && x > w - dp(96) && y < dp(72)) {
                state = PAUSED;
                saveProgress();
            }
        }

        private void updateControls(MotionEvent event) {
            int action = event.getActionMasked();
            boolean up = action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL;
            boolean pointerUp = action == MotionEvent.ACTION_POINTER_UP;
            int lifted = pointerUp || up ? event.getActionIndex() : -1;
            leftPressed = false;
            rightPressed = false;
            jumpPressed = false;
            if (up && !pointerUp) {
                return;
            }
            float w = getWidth();
            float h = getHeight();
            for (int i = 0; i < event.getPointerCount(); i++) {
                if (i == lifted) {
                    continue;
                }
                float x = event.getX(i);
                float y = event.getY(i);
                if (y < h * 0.60f) {
                    continue;
                }
                if (x < w * 0.22f) {
                    leftPressed = true;
                } else if (x < w * 0.44f) {
                    rightPressed = true;
                } else if (x > w * 0.68f) {
                    jumpPressed = true;
                }
            }
        }

        private void update(float dt) {
            if (state != PLAYING) {
                if (toastTimer > 0f) {
                    toastTimer -= dt;
                }
                return;
            }
            Level level = currentLevel();
            runTime += dt;
            if (toastTimer > 0f) {
                toastTimer -= dt;
            }
            updatePlayer(dt, level);
            updateEnemies(dt, level);
            updateCollectibles(level);
            checkHazards(level);
            checkGoal(level);
            float target = playerX - getWidth() / scale() * 0.42f;
            cameraX += (target - cameraX) * Math.min(1f, dt * 7f);
            cameraX = clamp(cameraX, 0f, Math.max(0f, level.width - getWidth() / scale()));
        }

        private void updatePlayer(float dt, Level level) {
            float accel = onGround ? 2300f : 1200f;
            float maxSpeed = 340f;
            if (leftPressed) {
                playerVx -= accel * dt;
                facingRight = false;
            }
            if (rightPressed) {
                playerVx += accel * dt;
                facingRight = true;
            }
            if (!leftPressed && !rightPressed) {
                float drag = onGround ? 0.82f : 0.96f;
                playerVx *= (float) Math.pow(drag, dt * 60f);
            }
            playerVx = clamp(playerVx, -maxSpeed, maxSpeed);
            if (jumpPressed && !jumpWasDown && onGround) {
                playerVy = -780f;
                onGround = false;
                performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
            }
            jumpWasDown = jumpPressed;
            playerVy += 2100f * dt;
            playerVy = Math.min(playerVy, 1100f);

            float oldX = playerX;
            float oldY = playerY;
            playerX += playerVx * dt;
            resolveHorizontal(level);
            playerY += playerVy * dt;
            onGround = false;
            resolveVertical(level, oldY);
            playerX = clamp(playerX, 0f, level.width - playerWidth());
            if (playerY > 920f) {
                loseLife();
            }
            if (Math.abs(playerX - oldX) > 1f || Math.abs(playerY - oldY) > 1f) {
                score = coins * 10 + gems * 50 + levelIndex * 500;
            }
        }

        private void resolveHorizontal(Level level) {
            RectF player = playerRect();
            for (Platform p : level.platforms) {
                if (RectF.intersects(player, p.rect)) {
                    if (playerVx > 0f) {
                        playerX = p.rect.left - playerWidth();
                    } else if (playerVx < 0f) {
                        playerX = p.rect.right;
                    }
                    playerVx = 0f;
                    player = playerRect();
                }
            }
        }

        private void resolveVertical(Level level, float oldY) {
            RectF player = playerRect();
            float oldBottom = oldY + playerHeight();
            for (Platform p : level.platforms) {
                if (RectF.intersects(player, p.rect)) {
                    if (playerVy > 0f && oldBottom <= p.rect.top + 16f) {
                        playerY = p.rect.top - playerHeight();
                        playerVy = 0f;
                        onGround = true;
                    } else if (playerVy < 0f) {
                        playerY = p.rect.bottom;
                        playerVy = 0f;
                    }
                    player = playerRect();
                }
            }
        }

        private void updateEnemies(float dt, Level level) {
            RectF player = playerRect();
            float oldVy = playerVy;
            for (Enemy e : level.enemies) {
                if (!e.alive) {
                    continue;
                }
                e.x += e.dir * e.speed * dt;
                if (e.x < e.minX) {
                    e.x = e.minX;
                    e.dir = 1f;
                } else if (e.x > e.maxX) {
                    e.x = e.maxX;
                    e.dir = -1f;
                }
                RectF enemy = new RectF(e.x, e.y, e.x + e.w, e.y + e.h);
                if (RectF.intersects(player, enemy)) {
                    if (oldVy > 0f && player.bottom < enemy.centerY()) {
                        e.alive = false;
                        playerVy = -520f;
                        coins += 3;
                        showToast("Drone destroyed +3 coins");
                    } else {
                        loseLife();
                        return;
                    }
                }
            }
        }

        private void updateCollectibles(Level level) {
            RectF player = playerRect();
            for (Pickup c : level.pickups) {
                if (c.taken) {
                    continue;
                }
                RectF item = new RectF(c.x - 18f, c.y - 18f, c.x + 18f, c.y + 18f);
                if (RectF.intersects(player, item)) {
                    c.taken = true;
                    if (c.gem) {
                        gems++;
                        showToast("Crystal +1");
                    } else {
                        coins++;
                    }
                }
            }
        }

        private void checkHazards(Level level) {
            RectF player = playerRect();
            for (RectF h : level.hazards) {
                if (RectF.intersects(player, h)) {
                    loseLife();
                    return;
                }
            }
            if (playerX > level.checkpointX && checkpointX < level.checkpointX) {
                checkpointX = level.checkpointX;
                checkpointY = level.checkpointY;
                showToast("Checkpoint");
            }
        }

        private void checkGoal(Level level) {
            RectF goal = new RectF(level.goalX - 20f, level.goalY - 100f, level.goalX + 70f, level.goalY + 20f);
            if (RectF.intersects(playerRect(), goal)) {
                if (levelIndex == levels.size() - 1) {
                    state = COMPLETED;
                    unlockedLevel = Math.max(unlockedLevel, levels.size() - 1);
                } else {
                    state = LEVEL_CLEAR;
                    unlockedLevel = Math.max(unlockedLevel, levelIndex + 1);
                }
                if (bestTime <= 0f || runTime < bestTime) {
                    bestTime = runTime;
                }
                saveProgress();
            }
        }

        private void loseLife() {
            lives--;
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
            if (lives <= 0) {
                state = GAME_OVER;
                saveProgress();
                return;
            }
            playerX = checkpointX;
            playerY = checkpointY;
            playerVx = 0f;
            playerVy = 0f;
            cameraX = Math.max(0f, playerX - 320f);
            showToast("Try again. Lives " + lives);
        }

        private void nextLevel() {
            if (levelIndex < levels.size() - 1) {
                levelIndex++;
                resetLevel(levelIndex, true);
                state = PLAYING;
                saveProgress();
            } else {
                state = COMPLETED;
            }
        }

        private void newGame() {
            levelIndex = 0;
            unlockedLevel = Math.max(unlockedLevel, 0);
            coins = 0;
            gems = 0;
            score = 0;
            lives = 3;
            resetLevel(0, true);
            saveProgress();
        }

        private void resetLevel(int index, boolean fullReset) {
            levelIndex = clampIndex(index);
            Level level = currentLevel();
            if (fullReset) {
                lives = 3;
            }
            runTime = 0f;
            level.reset();
            playerX = level.startX;
            playerY = level.startY;
            playerVx = 0f;
            playerVy = 0f;
            checkpointX = level.startX;
            checkpointY = level.startY;
            cameraX = 0f;
            onGround = false;
            jumpWasDown = false;
            showToast(level.name);
        }

        private void loadProgress() {
            levelIndex = clampIndex(prefs.getInt("level", 0));
            unlockedLevel = clampIndex(prefs.getInt("unlocked", 0));
            coins = prefs.getInt("coins", 0);
            gems = prefs.getInt("gems", 0);
            bestTime = prefs.getFloat("best_time", 0f);
        }

        private void saveProgress() {
            prefs.edit()
                .putInt("level", levelIndex)
                .putInt("unlocked", unlockedLevel)
                .putInt("coins", coins)
                .putInt("gems", gems)
                .putFloat("best_time", bestTime)
                .apply();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float s = scale();
            canvas.save();
            canvas.scale(s, s);
            drawWorld(canvas);
            canvas.restore();
            drawHud(canvas);
            drawControls(canvas);
            if (state != PLAYING) {
                drawOverlay(canvas);
            }
            if (toastTimer > 0f && toast.length() > 0) {
                drawToast(canvas);
            }
        }

        private void drawWorld(Canvas canvas) {
            float sw = getWidth() / scale();
            float sh = getHeight() / scale();
            Level level = currentLevel();
            drawSky(canvas, sw, sh, level.theme);
            canvas.translate(-cameraX, 0f);
            drawBackdrop(canvas, level, sh);
            for (Platform p : level.platforms) {
                drawPlatform(canvas, p, level.theme);
            }
            for (RectF hazard : level.hazards) {
                drawHazard(canvas, hazard);
            }
            drawCheckpoint(canvas, level);
            drawGoal(canvas, level);
            for (Pickup p : level.pickups) {
                if (!p.taken) {
                    drawPickup(canvas, p);
                }
            }
            for (Enemy e : level.enemies) {
                if (e.alive) {
                    drawEnemy(canvas, e);
                }
            }
            drawPlayer(canvas);
        }

        private void drawSky(Canvas canvas, float sw, float sh, int theme) {
            int top = theme == 0 ? 0xFF3E5CCB : theme == 1 ? 0xFF31195F : 0xFF071421;
            int bottom = theme == 0 ? 0xFFFFA55F : theme == 1 ? 0xFF1F8CA7 : 0xFF0E5B6F;
            paint.setShader(new LinearGradient(0f, 0f, 0f, sh, top, bottom, Shader.TileMode.CLAMP));
            paint.setStyle(Paint.Style.FILL);
            canvas.drawRect(0f, 0f, sw, sh, paint);
            paint.setShader(null);
            paint.setColor(0x55FFFFFF);
            canvas.drawCircle(sw * 0.80f, sh * 0.18f, 34f, paint);
        }

        private void drawBackdrop(Canvas canvas, Level level, float sh) {
            float parallax = cameraX * 0.30f;
            paint.setStyle(Paint.Style.FILL);
            for (int i = -1; i < 9; i++) {
                float base = i * 420f - parallax;
                path.reset();
                path.moveTo(base, sh * 0.78f);
                path.lineTo(base + 180f, sh * 0.34f + (i % 2) * 42f);
                path.lineTo(base + 420f, sh * 0.78f);
                path.close();
                paint.setColor(level.theme == 2 ? 0x9932D5FF : 0x88451478);
                canvas.drawPath(path, paint);
            }
            paint.setColor(0x22000000);
            for (int x = -400; x < level.width + 900; x += 120) {
                canvas.drawLine(x, sh * 0.82f, x + 240f, sh, paint);
                canvas.drawLine(x + 220f, sh * 0.82f, x - 40f, sh, paint);
            }
        }

        private void drawPlatform(Canvas canvas, Platform p, int theme) {
            int top = theme == 0 ? 0xFF36A66F : theme == 1 ? 0xFFB96C3B : 0xFF3959D8;
            int side = theme == 0 ? 0xFF176449 : theme == 1 ? 0xFF6E3B2C : 0xFF1C2E8E;
            rect.set(p.rect);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(side);
            canvas.drawRect(rect.left, rect.top + 18f, rect.right, rect.bottom + 18f, paint);
            paint.setColor(top);
            canvas.drawRoundRect(rect, 8f, 8f, paint);
            paint.setColor(0x88FFFFFF);
            canvas.drawRect(rect.left, rect.top, rect.right, rect.top + 5f, paint);
            paint.setColor(0x26000000);
            for (float x = rect.left + 26f; x < rect.right; x += 56f) {
                canvas.drawLine(x, rect.top + 8f, x + 20f, rect.bottom - 4f, paint);
            }
        }

        private void drawHazard(Canvas canvas, RectF hazard) {
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(0xFFE94646);
            for (float x = hazard.left; x < hazard.right; x += 34f) {
                path.reset();
                path.moveTo(x, hazard.bottom);
                path.lineTo(x + 17f, hazard.top);
                path.lineTo(x + 34f, hazard.bottom);
                path.close();
                canvas.drawPath(path, paint);
            }
        }

        private void drawCheckpoint(Canvas canvas, Level level) {
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(checkpointX >= level.checkpointX ? 0xFFFFD45A : 0xFFEBF3FF);
            canvas.drawRect(level.checkpointX, level.checkpointY + 4f, level.checkpointX + 8f, level.checkpointY + 96f, paint);
            path.reset();
            path.moveTo(level.checkpointX + 8f, level.checkpointY + 8f);
            path.lineTo(level.checkpointX + 74f, level.checkpointY + 24f);
            path.lineTo(level.checkpointX + 8f, level.checkpointY + 44f);
            path.close();
            canvas.drawPath(path, paint);
        }

        private void drawGoal(Canvas canvas, Level level) {
            float pulse = (float) Math.sin(System.currentTimeMillis() / 220.0) * 8f;
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(0x5532D5FF);
            canvas.drawCircle(level.goalX + 22f, level.goalY - 52f, 62f + pulse, paint);
            paint.setColor(0xFFBFFFFF);
            canvas.drawRoundRect(level.goalX, level.goalY - 110f, level.goalX + 48f, level.goalY + 6f, 24f, 24f, paint);
            paint.setColor(0xFF1A1E54);
            canvas.drawRoundRect(level.goalX + 10f, level.goalY - 92f, level.goalX + 38f, level.goalY - 16f, 14f, 14f, paint);
        }

        private void drawPickup(Canvas canvas, Pickup p) {
            float spin = (float) Math.sin(System.currentTimeMillis() / 150.0 + p.x * 0.01f);
            paint.setStyle(Paint.Style.FILL);
            if (p.gem) {
                paint.setColor(0xFF9C5CFF);
                path.reset();
                path.moveTo(p.x, p.y - 23f);
                path.lineTo(p.x + 23f, p.y);
                path.lineTo(p.x, p.y + 23f);
                path.lineTo(p.x - 23f, p.y);
                path.close();
                canvas.drawPath(path, paint);
                paint.setColor(0xAAFFFFFF);
                canvas.drawCircle(p.x - 6f, p.y - 6f, 5f, paint);
            } else {
                paint.setColor(0xFFFFD45A);
                canvas.drawOval(p.x - 18f * Math.abs(spin), p.y - 20f, p.x + 18f * Math.abs(spin), p.y + 20f, paint);
                paint.setColor(0xFF9E671A);
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(3f);
                canvas.drawOval(p.x - 12f * Math.abs(spin), p.y - 14f, p.x + 12f * Math.abs(spin), p.y + 14f, paint);
            }
        }

        private void drawEnemy(Canvas canvas, Enemy e) {
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(0x66000000);
            canvas.drawOval(e.x + 4f, e.y + e.h - 4f, e.x + e.w - 4f, e.y + e.h + 12f, paint);
            paint.setColor(0xFF202638);
            canvas.drawRoundRect(e.x, e.y, e.x + e.w, e.y + e.h, 14f, 14f, paint);
            paint.setColor(0xFF32D5FF);
            canvas.drawCircle(e.x + 16f, e.y + 20f, 6f, paint);
            canvas.drawCircle(e.x + e.w - 16f, e.y + 20f, 6f, paint);
            paint.setColor(0xFFE5485E);
            canvas.drawRect(e.x + 12f, e.y + e.h - 10f, e.x + e.w - 12f, e.y + e.h - 4f, paint);
        }

        private void drawPlayer(Canvas canvas) {
            float x = playerX;
            float y = playerY;
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(0x66000000);
            canvas.drawOval(x - 10f, y + playerHeight() - 4f, x + playerWidth() + 10f, y + playerHeight() + 14f, paint);
            paint.setColor(0xFF101827);
            canvas.drawRoundRect(x + 7f, y + 26f, x + 43f, y + 76f, 9f, 9f, paint);
            paint.setColor(0xFFEBB87D);
            canvas.drawRoundRect(x + 9f, y, x + 43f, y + 34f, 13f, 13f, paint);
            paint.setColor(0xFF55341F);
            canvas.drawRect(x + 8f, y, x + 44f, y + 13f, paint);
            paint.setColor(0xFF32D5FF);
            float eyeX = facingRight ? x + 33f : x + 18f;
            canvas.drawCircle(eyeX, y + 18f, 4f, paint);
            paint.setColor(0xFFFFD45A);
            canvas.drawRect(x + 4f, y + 42f, x + 12f, y + 70f, paint);
            canvas.drawRect(x + 38f, y + 42f, x + 48f, y + 70f, paint);
            paint.setColor(0xFFEBF3FF);
            canvas.drawRect(x + 9f, y + 76f, x + 22f, y + playerHeight(), paint);
            canvas.drawRect(x + 28f, y + 76f, x + 43f, y + playerHeight(), paint);
        }

        private void drawHud(Canvas canvas) {
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(0xA4070A12);
            canvas.drawRoundRect(dp(12), dp(10), getWidth() - dp(12), dp(58), dp(16), dp(16), paint);
            paint.setColor(0xFFF8FBFF);
            paint.setFakeBoldText(true);
            paint.setTextSize(dp(17));
            canvas.drawText("VELVET RUN 64", dp(28), dp(40), paint);
            paint.setFakeBoldText(false);
            paint.setTextSize(dp(14));
            String hud = "L" + (levelIndex + 1) + "  " + currentLevel().name
                + "   Coins " + coins
                + "   Crystals " + gems
                + "   Lives " + lives
                + "   Time " + formatTime(runTime);
            canvas.drawText(hud, dp(190), dp(40), paint);
            paint.setFakeBoldText(true);
            canvas.drawText("PAUSA", getWidth() - dp(84), dp(40), paint);
            paint.setFakeBoldText(false);
        }

        private void drawControls(Canvas canvas) {
            if (state != PLAYING) {
                return;
            }
            float h = getHeight();
            drawControlButton(canvas, dp(84), h - dp(82), "<", leftPressed);
            drawControlButton(canvas, dp(194), h - dp(82), ">", rightPressed);
            drawControlButton(canvas, getWidth() - dp(114), h - dp(82), "JUMP", jumpPressed);
        }

        private void drawControlButton(Canvas canvas, float cx, float cy, String label, boolean active) {
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(active ? 0xAA32D5FF : 0x55101826);
            canvas.drawCircle(cx, cy, dp(label.length() > 1 ? 50 : 42), paint);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(2));
            paint.setColor(0xCCF8FBFF);
            canvas.drawCircle(cx, cy, dp(label.length() > 1 ? 50 : 42), paint);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(0xFFF8FBFF);
            paint.setTextAlign(Paint.Align.CENTER);
            paint.setFakeBoldText(true);
            paint.setTextSize(dp(label.length() > 1 ? 14 : 28));
            canvas.drawText(label, cx, cy + dp(label.length() > 1 ? 5 : 10), paint);
            paint.setTextAlign(Paint.Align.LEFT);
            paint.setFakeBoldText(false);
        }

        private void drawOverlay(Canvas canvas) {
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(0xC8050811);
            canvas.drawRect(0f, 0f, getWidth(), getHeight(), paint);
            paint.setTextAlign(Paint.Align.CENTER);
            paint.setFakeBoldText(true);
            paint.setColor(0xFFF8FBFF);
            paint.setTextSize(dp(44));
            String title;
            String line1;
            String line2;
            if (state == MENU) {
                title = "VELVET RUN 64";
                line1 = "Tocca qui per partire";
                line2 = "Tocca in basso per nuova partita";
            } else if (state == PAUSED) {
                title = "PAUSA";
                line1 = "Tocca in alto per continuare";
                line2 = "Tocca in basso per tornare al menu";
            } else if (state == LEVEL_CLEAR) {
                title = "LIVELLO COMPLETATO";
                line1 = "Coins " + coins + "  Crystals " + gems;
                line2 = "Tocca per il prossimo livello";
            } else if (state == COMPLETED) {
                title = "TOUR COMPLETATO";
                line1 = "Score " + score + "  Best " + formatTime(bestTime);
                line2 = "Tocca per ricominciare";
            } else {
                title = "GAME OVER";
                line1 = "Score " + score;
                line2 = "Tocca per riprovare";
            }
            canvas.drawText(title, getWidth() / 2f, getHeight() * 0.29f, paint);
            paint.setTextSize(dp(20));
            paint.setColor(0xFF32D5FF);
            canvas.drawText(line1, getWidth() / 2f, getHeight() * 0.50f, paint);
            paint.setColor(0xFFFFD45A);
            canvas.drawText(line2, getWidth() / 2f, getHeight() * 0.66f, paint);
            paint.setColor(0xFFB8C5D6);
            paint.setTextSize(dp(14));
            canvas.drawText("Retro 2.5D platformer - controlli touch: sinistra, destra, salto", getWidth() / 2f, getHeight() * 0.82f, paint);
            paint.setTextAlign(Paint.Align.LEFT);
            paint.setFakeBoldText(false);
        }

        private void drawToast(Canvas canvas) {
            paint.setTextSize(dp(16));
            paint.setFakeBoldText(true);
            float width = paint.measureText(toast) + dp(34);
            float left = (getWidth() - width) / 2f;
            float top = dp(72);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(0xD8070A12);
            canvas.drawRoundRect(left, top, left + width, top + dp(42), dp(16), dp(16), paint);
            paint.setColor(0xFFF8FBFF);
            paint.setTextAlign(Paint.Align.CENTER);
            canvas.drawText(toast, getWidth() / 2f, top + dp(27), paint);
            paint.setTextAlign(Paint.Align.LEFT);
            paint.setFakeBoldText(false);
        }

        private void showToast(String text) {
            toast = text;
            toastTimer = 2.2f;
        }

        private float playerWidth() {
            return 52f;
        }

        private float playerHeight() {
            return 96f;
        }

        private RectF playerRect() {
            return new RectF(playerX + 6f, playerY + 4f, playerX + playerWidth() - 6f, playerY + playerHeight());
        }

        private Level currentLevel() {
            return levels.get(clampIndex(levelIndex));
        }

        private int clampIndex(int index) {
            return Math.max(0, Math.min(levels.size() - 1, index));
        }

        private float scale() {
            return Math.max(0.70f, getHeight() / 720f);
        }

        private float clamp(float value, float min, float max) {
            return Math.max(min, Math.min(max, value));
        }

        private String formatTime(float seconds) {
            int total = (int) seconds;
            return (total / 60) + ":" + (total % 60 < 10 ? "0" : "") + (total % 60);
        }

        private int dp(int value) {
            return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
        }

        private void buildLevels() {
            Level coast = new Level("Neon Coast", 0, 3700f, 110f, 430f, 1540f, 390f, 3460f, 468f);
            coast.platform(0f, 560f, 760f, 90f);
            coast.platform(860f, 505f, 300f, 70f);
            coast.platform(1250f, 455f, 340f, 70f);
            coast.platform(1720f, 540f, 680f, 90f);
            coast.platform(2510f, 470f, 360f, 70f);
            coast.platform(2990f, 535f, 620f, 90f);
            coast.hazard(760f, 632f, 1120f, 676f);
            coast.hazard(2420f, 612f, 2780f, 676f);
            coast.enemy(1020f, 475f, 900f, 1140f);
            coast.enemy(2050f, 510f, 1810f, 2350f);
            coast.pickups(220f, 510f, false, 5, 80f);
            coast.pickups(930f, 455f, false, 3, 70f);
            coast.pickups(1320f, 405f, true, 1, 1f);
            coast.pickups(1820f, 490f, false, 6, 70f);
            coast.pickups(2580f, 420f, true, 1, 1f);
            levels.add(coast);

            Level skyline = new Level("Crystal Skyline", 1, 4300f, 100f, 430f, 2040f, 320f, 4040f, 388f);
            skyline.platform(0f, 560f, 520f, 90f);
            skyline.platform(650f, 490f, 260f, 70f);
            skyline.platform(1060f, 410f, 280f, 70f);
            skyline.platform(1510f, 345f, 330f, 70f);
            skyline.platform(1950f, 445f, 430f, 70f);
            skyline.platform(2530f, 520f, 520f, 90f);
            skyline.platform(3200f, 450f, 280f, 70f);
            skyline.platform(3710f, 455f, 520f, 90f);
            skyline.hazard(520f, 630f, 760f, 676f);
            skyline.hazard(1360f, 530f, 1560f, 676f);
            skyline.hazard(3060f, 625f, 3450f, 676f);
            skyline.enemy(715f, 460f, 665f, 875f);
            skyline.enemy(2100f, 415f, 1980f, 2340f);
            skyline.enemy(3330f, 420f, 3210f, 3460f);
            skyline.pickups(690f, 440f, false, 4, 62f);
            skyline.pickups(1120f, 360f, true, 1, 1f);
            skyline.pickups(1570f, 295f, false, 5, 55f);
            skyline.pickups(2600f, 470f, false, 6, 64f);
            skyline.pickups(3245f, 400f, true, 1, 1f);
            levels.add(skyline);

            Level vault = new Level("Moon Vault", 2, 4700f, 100f, 430f, 2320f, 320f, 4420f, 368f);
            vault.platform(0f, 560f, 480f, 90f);
            vault.platform(650f, 510f, 260f, 70f);
            vault.platform(1060f, 465f, 230f, 70f);
            vault.platform(1450f, 410f, 270f, 70f);
            vault.platform(1920f, 350f, 360f, 70f);
            vault.platform(2460f, 470f, 560f, 90f);
            vault.platform(3180f, 400f, 280f, 70f);
            vault.platform(3620f, 345f, 280f, 70f);
            vault.platform(4100f, 435f, 520f, 90f);
            vault.hazard(480f, 630f, 720f, 676f);
            vault.hazard(1300f, 560f, 1510f, 676f);
            vault.hazard(2280f, 620f, 2600f, 676f);
            vault.hazard(3460f, 560f, 3740f, 676f);
            vault.enemy(760f, 480f, 670f, 880f);
            vault.enemy(2050f, 320f, 1940f, 2240f);
            vault.enemy(3340f, 370f, 3210f, 3440f);
            vault.enemy(4320f, 405f, 4130f, 4570f);
            vault.pickups(710f, 460f, false, 4, 55f);
            vault.pickups(1100f, 415f, true, 1, 1f);
            vault.pickups(1500f, 360f, false, 5, 55f);
            vault.pickups(2020f, 300f, true, 1, 1f);
            vault.pickups(2520f, 420f, false, 7, 65f);
            vault.pickups(3650f, 300f, true, 1, 1f);
            levels.add(vault);
        }

        private static final class Level {
            final String name;
            final int theme;
            final float width;
            final float startX;
            final float startY;
            final float checkpointX;
            final float checkpointY;
            final float goalX;
            final float goalY;
            final ArrayList<Platform> platforms = new ArrayList<Platform>();
            final ArrayList<Pickup> pickups = new ArrayList<Pickup>();
            final ArrayList<Enemy> enemies = new ArrayList<Enemy>();
            final ArrayList<RectF> hazards = new ArrayList<RectF>();

            Level(String name, int theme, float width, float startX, float startY, float checkpointX, float checkpointY, float goalX, float goalY) {
                this.name = name;
                this.theme = theme;
                this.width = width;
                this.startX = startX;
                this.startY = startY;
                this.checkpointX = checkpointX;
                this.checkpointY = checkpointY;
                this.goalX = goalX;
                this.goalY = goalY;
            }

            void platform(float x, float y, float w, float h) {
                platforms.add(new Platform(x, y, w, h));
            }

            void hazard(float l, float t, float r, float b) {
                hazards.add(new RectF(l, t, r, b));
            }

            void enemy(float x, float y, float minX, float maxX) {
                enemies.add(new Enemy(x, y, minX, maxX));
            }

            void pickups(float x, float y, boolean gem, int count, float gap) {
                for (int i = 0; i < count; i++) {
                    pickups.add(new Pickup(x + i * gap, y, gem));
                }
            }

            void reset() {
                for (int i = 0; i < pickups.size(); i++) {
                    pickups.get(i).taken = false;
                }
                for (int i = 0; i < enemies.size(); i++) {
                    enemies.get(i).reset();
                }
            }
        }

        private static final class Platform {
            final RectF rect;

            Platform(float x, float y, float w, float h) {
                rect = new RectF(x, y, x + w, y + h);
            }
        }

        private static final class Pickup {
            final float x;
            final float y;
            final boolean gem;
            boolean taken;

            Pickup(float x, float y, boolean gem) {
                this.x = x;
                this.y = y;
                this.gem = gem;
            }
        }

        private static final class Enemy {
            final float startX;
            final float y;
            final float minX;
            final float maxX;
            final float w = 58f;
            final float h = 48f;
            final float speed = 96f;
            float x;
            float dir = 1f;
            boolean alive = true;

            Enemy(float x, float y, float minX, float maxX) {
                this.startX = x;
                this.x = x;
                this.y = y;
                this.minX = minX;
                this.maxX = maxX;
            }

            void reset() {
                x = startX;
                dir = 1f;
                alive = true;
            }
        }
    }
}
