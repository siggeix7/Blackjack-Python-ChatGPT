package com.siggeix7.blackjack;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.View;

final class PlayingCardView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF rect = new RectF();
    private Card card;
    private boolean hidden;

    PlayingCardView(Context context, Card card, boolean hidden) {
        super(context);
        this.card = card;
        this.hidden = hidden;
        setLayerType(View.LAYER_TYPE_SOFTWARE, null);
    }

    void setCard(Card card, boolean hidden) {
        this.card = card;
        this.hidden = hidden;
        invalidate();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = dp(64);
        int height = dp(92);
        setMeasuredDimension(resolveSize(width, widthMeasureSpec), resolveSize(height, heightMeasureSpec));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float w = getWidth();
        float h = getHeight();
        rect.set(dp(2), dp(2), w - dp(2), h - dp(2));
        paint.setShadowLayer(dp(3), 0, dp(2), 0x66000000);
        if (hidden || card == null) {
            drawBack(canvas, rect);
        } else {
            drawFace(canvas, rect);
        }
        paint.clearShadowLayer();
    }

    private void drawFace(Canvas canvas, RectF r) {
        float width = r.width();
        float height = r.height();
        float radius = width * 0.14f;
        float stroke = Math.max(1f, width * 0.018f);
        float leftPad = width * 0.11f;
        float topPad = height * 0.075f;
        float rankSize = width * (card.rank.length() > 1 ? 0.23f : 0.30f);
        int suitColor = card.isRed() ? 0xFFE33E55 : 0xFF111827;
        int accent = card.isRed() ? 0xFFE5485E : 0xFF32D5FF;

        paint.setStyle(Paint.Style.FILL);
        paint.setShader(new LinearGradient(0, r.top, 0, r.bottom, Color.WHITE, 0xFFE7EEF7, Shader.TileMode.CLAMP));
        canvas.drawRoundRect(r, radius, radius, paint);
        paint.setShader(null);
        paint.setColor(0x17000000);
        canvas.drawRoundRect(r.left + width * 0.05f, r.top + height * 0.48f, r.right - width * 0.05f, r.bottom - height * 0.05f, radius * 0.75f, radius * 0.75f, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(stroke);
        paint.setColor(accent);
        canvas.drawRoundRect(r, radius, radius, paint);
        paint.setStrokeWidth(Math.max(1f, width * 0.010f));
        paint.setColor(0x55000000);
        canvas.drawRoundRect(r.left + width * 0.055f, r.top + width * 0.055f, r.right - width * 0.055f, r.bottom - width * 0.055f, radius * 0.70f, radius * 0.70f, paint);

        paint.setStyle(Paint.Style.FILL);
        paint.setColor(suitColor);
        paint.setTextAlign(Paint.Align.LEFT);
        paint.setFakeBoldText(true);
        paint.setTextSize(rankSize);
        canvas.drawText(card.rank, r.left + leftPad, r.top + topPad + rankSize * 0.82f, paint);
        drawSuit(canvas, card.suit, r.left + leftPad + width * 0.11f, r.top + topPad + rankSize * 1.34f, width * 0.18f, suitColor);

        paint.setColor(card.isRed() ? 0x1FE5485E : 0x1F0F172A);
        canvas.drawCircle(r.centerX(), r.centerY() + height * 0.04f, width * 0.27f, paint);
        drawSuit(canvas, card.suit, r.centerX(), r.centerY() + height * 0.04f, width * 0.43f, suitColor);

        canvas.save();
        canvas.rotate(180, r.centerX(), r.centerY());
        paint.setTextAlign(Paint.Align.LEFT);
        paint.setTextSize(rankSize);
        canvas.drawText(card.rank, r.left + leftPad, r.top + topPad + rankSize * 0.82f, paint);
        drawSuit(canvas, card.suit, r.left + leftPad + width * 0.11f, r.top + topPad + rankSize * 1.34f, width * 0.19f, suitColor);
        canvas.restore();
        paint.setFakeBoldText(false);
    }

    private void drawBack(Canvas canvas, RectF r) {
        float width = r.width();
        float height = r.height();
        float radius = width * 0.14f;
        paint.setStyle(Paint.Style.FILL);
        paint.setShader(new LinearGradient(r.left, r.top, r.right, r.bottom, 0xFF050A12, 0xFF1E1B4B, Shader.TileMode.CLAMP));
        canvas.drawRoundRect(r, radius, radius, paint);
        paint.setShader(null);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(Math.max(1f, width * 0.030f));
        paint.setColor(0xFF32D5FF);
        float inset = width * 0.11f;
        canvas.drawRoundRect(r.left + inset, r.top + inset, r.right - inset, r.bottom - inset, radius * 0.72f, radius * 0.72f, paint);

        paint.setStrokeWidth(Math.max(1f, width * 0.014f));
        paint.setColor(0x77FFC85A);
        path.reset();
        path.moveTo(r.centerX(), r.top + height * 0.19f);
        path.lineTo(r.right - width * 0.22f, r.centerY());
        path.lineTo(r.centerX(), r.bottom - height * 0.19f);
        path.lineTo(r.left + width * 0.22f, r.centerY());
        path.close();
        canvas.drawPath(path, paint);
        paint.setColor(0x5532D5FF);
        for (int i = 0; i < 3; i++) {
            float dx = width * (0.12f + i * 0.10f);
            canvas.drawLine(r.left + dx, r.top + height * 0.20f, r.right - dx, r.bottom - height * 0.20f, paint);
            canvas.drawLine(r.right - dx, r.top + height * 0.20f, r.left + dx, r.bottom - height * 0.20f, paint);
        }
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xEAF8FBFF);
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setFakeBoldText(true);
        paint.setTextSize(width * 0.31f);
        canvas.drawText("21", r.centerX(), r.centerY() + height * 0.08f, paint);
        paint.setFakeBoldText(false);
    }

    private void drawSuit(Canvas canvas, char suit, float cx, float cy, float size, int color) {
        paint.setShader(null);
        paint.setColor(color);
        paint.setStyle(Paint.Style.FILL);
        path.reset();
        if (suit == '♥') {
            path.moveTo(cx, cy + size * 0.45f);
            path.cubicTo(cx - size * 0.55f, cy + size * 0.08f, cx - size * 0.48f, cy - size * 0.34f, cx - size * 0.18f, cy - size * 0.34f);
            path.cubicTo(cx - size * 0.04f, cy - size * 0.34f, cx, cy - size * 0.22f, cx, cy - size * 0.12f);
            path.cubicTo(cx, cy - size * 0.22f, cx + size * 0.04f, cy - size * 0.34f, cx + size * 0.18f, cy - size * 0.34f);
            path.cubicTo(cx + size * 0.48f, cy - size * 0.34f, cx + size * 0.55f, cy + size * 0.08f, cx, cy + size * 0.45f);
            path.close();
            canvas.drawPath(path, paint);
        } else if (suit == '♦') {
            path.moveTo(cx, cy - size * 0.50f);
            path.lineTo(cx + size * 0.42f, cy);
            path.lineTo(cx, cy + size * 0.50f);
            path.lineTo(cx - size * 0.42f, cy);
            path.close();
            canvas.drawPath(path, paint);
        } else if (suit == '♣') {
            canvas.drawCircle(cx, cy - size * 0.22f, size * 0.24f, paint);
            canvas.drawCircle(cx - size * 0.24f, cy + size * 0.05f, size * 0.24f, paint);
            canvas.drawCircle(cx + size * 0.24f, cy + size * 0.05f, size * 0.24f, paint);
            path.moveTo(cx - size * 0.09f, cy + size * 0.20f);
            path.lineTo(cx + size * 0.09f, cy + size * 0.20f);
            path.lineTo(cx + size * 0.18f, cy + size * 0.48f);
            path.lineTo(cx - size * 0.18f, cy + size * 0.48f);
            path.close();
            canvas.drawPath(path, paint);
        } else {
            path.moveTo(cx, cy - size * 0.50f);
            path.cubicTo(cx - size * 0.55f, cy - size * 0.10f, cx - size * 0.46f, cy + size * 0.28f, cx - size * 0.15f, cy + size * 0.28f);
            path.cubicTo(cx - size * 0.04f, cy + size * 0.28f, cx, cy + size * 0.18f, cx, cy + size * 0.08f);
            path.cubicTo(cx, cy + size * 0.18f, cx + size * 0.04f, cy + size * 0.28f, cx + size * 0.15f, cy + size * 0.28f);
            path.cubicTo(cx + size * 0.46f, cy + size * 0.28f, cx + size * 0.55f, cy - size * 0.10f, cx, cy - size * 0.50f);
            path.close();
            canvas.drawPath(path, paint);
            path.reset();
            path.moveTo(cx - size * 0.08f, cy + size * 0.18f);
            path.lineTo(cx + size * 0.08f, cy + size * 0.18f);
            path.lineTo(cx + size * 0.18f, cy + size * 0.48f);
            path.lineTo(cx - size * 0.18f, cy + size * 0.48f);
            path.close();
            canvas.drawPath(path, paint);
        }
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }
}
