package com.example.myapplication;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedList;
import java.util.List;

public class OverlayView extends View {

    private final List<Box> boundingBoxes = new LinkedList<>();
    private final Paint boxPaint;
    private final Paint textPaint;
    private final Paint textBackgroundPaint;
    private final float cornerRadius;
    private final Rect textBounds = new Rect();

    // --- PENAMBAHAN: Kelas internal untuk membantu proses layout ---
    private static class Label {
        final String text;
        final RectF backgroundRect;

        Label(String text, RectF backgroundRect) {
            this.text = text;
            this.backgroundRect = backgroundRect;
        }
    }


    public OverlayView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);

        boxPaint = new Paint();
        boxPaint.setColor(ContextCompat.getColor(context, R.color.colorBackgroundRed));
        boxPaint.setStyle(Paint.Style.STROKE);
        boxPaint.setStrokeWidth(8f);

        textPaint = new Paint();
        textPaint.setColor(Color.WHITE);
        textPaint.setTextSize(40f);
        textPaint.setStyle(Paint.Style.FILL);

        textBackgroundPaint = new Paint();
        textBackgroundPaint.setColor(ContextCompat.getColor(context, R.color.colorBackgroundRed));
        textBackgroundPaint.setStyle(Paint.Style.FILL);

        cornerRadius = 12f;
    }

    public void clear() {
        synchronized (boundingBoxes) {
            boundingBoxes.clear();
        }
        postInvalidate();
    }

    public void add(Box box) {
        synchronized (boundingBoxes) {
            boundingBoxes.add(box);
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        // --- TAHAP 1: KUMPULKAN SEMUA INFORMASI LABEL DAN GAMBAR BOUNDING BOX ---
        List<Label> labelsToDraw = new ArrayList<>();
        synchronized (boundingBoxes) {
            for (Box box : boundingBoxes) {
                // Gambar bounding box terlebih dahulu
                canvas.drawRect(box.rect, boxPaint);

                // Siapkan informasi untuk label
                String text = box.label;
                textPaint.getTextBounds(text, 0, text.length(), textBounds);
                float textHeight = textBounds.height();
                float textWidth = textBounds.width();
                float padding = 16f;

                float x = box.rect.left;
                float y = box.rect.top;

                RectF backgroundRect = new RectF(
                        x,
                        y - textHeight - (padding * 2),
                        x + textWidth + (padding * 2),
                        y
                );

                // Pindahkan ke dalam box jika akan terpotong di atas
                if (backgroundRect.top < 0) {
                    backgroundRect.offset(0, -backgroundRect.top);
                }

                labelsToDraw.add(new Label(text, backgroundRect));
            }
        }

        // --- TAHAP 2: URUTKAN LABEL BERDASARKAN POSISI VERTIKALNYA ---
        Collections.sort(labelsToDraw, Comparator.comparingDouble(l -> l.backgroundRect.top));

        // --- TAHAP 3: GAMBAR LABEL SATU PER SATU DAN SESUAIKAN POSISI JIKA TUMPANG TINDIH ---
        float lastLabelBottom = -1f;

        for (Label label : labelsToDraw) {
            RectF currentRect = label.backgroundRect;

            // Periksa tumpang tindih dengan label sebelumnya
            if (lastLabelBottom != -1f && currentRect.top < lastLabelBottom) {
                // Jika tumpang tindih, geser label saat ini ke bawah
                float shift = lastLabelBottom - currentRect.top;
                currentRect.offset(0, shift);
            }

            // Gambar latar belakang dan teks pada posisi yang sudah final
            canvas.drawRoundRect(currentRect, cornerRadius, cornerRadius, textBackgroundPaint);

            float padding = 16f;
            float textY = currentRect.top + textBounds.height() + padding;
            canvas.drawText(label.text, currentRect.left + padding, textY, textPaint);

            // Perbarui posisi bawah dari label terakhir yang digambar
            lastLabelBottom = currentRect.bottom;
        }
    }

    public static class Box {
        public final RectF rect;
        public final String label;

        public Box(RectF rect, String label) {
            this.rect = rect;
            this.label = label;
        }
    }
}