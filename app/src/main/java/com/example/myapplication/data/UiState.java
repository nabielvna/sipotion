package com.example.myapplication.data;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.example.myapplication.MainViewModel;

import java.util.List;
import java.util.Locale;

public class UiState {

    @NonNull
    public final Status status;
    @Nullable
    public final String message;
    @Nullable
    public final List<MainViewModel.Prediction> predictions;

    // --- PENAMBAHAN: Simpan prediksi terakhir yang berhasil ---
    @Nullable
    public final List<MainViewModel.Prediction> lastSuccessfulPredictions;

    private UiState(@NonNull Status status, @Nullable String message, @Nullable List<MainViewModel.Prediction> predictions, @Nullable List<MainViewModel.Prediction> lastSuccessfulPredictions) {
        this.status = status;
        this.message = message;
        this.predictions = predictions;
        this.lastSuccessfulPredictions = lastSuccessfulPredictions;
    }

    // --- Perbarui semua factory method ---
    public static UiState ready() {
        return new UiState(Status.READY, "Ready to analyze", null, null);
    }

    // Method analyzing sekarang bisa membawa data lama
    public static UiState analyzing(@Nullable List<MainViewModel.Prediction> lastPredictions) {
        return new UiState(Status.ANALYZING, "Analyzing...", null, lastPredictions);
    }

    public static UiState success(List<MainViewModel.Prediction> predictions) {
        String resultText = String.format(Locale.US, "✓ %d object(s) detected", predictions.size());
        return new UiState(Status.SUCCESS, resultText, predictions, predictions); // Simpan juga sebagai last successful
    }

    public static UiState stopped() {
        return new UiState(Status.STOPPED, "Analysis stopped", null, null);
    }

    public static UiState error(String errorMessage, @Nullable List<MainViewModel.Prediction> lastPredictions) {
        return new UiState(Status.ERROR, "Error: " + errorMessage, null, lastPredictions);
    }

    public static UiState noDetection(@Nullable List<MainViewModel.Prediction> lastPredictions) {
        return new UiState(Status.ANALYZING, "Analyzing... (No object detected)", null, lastPredictions);
    }

    public enum Status {
        READY,
        ANALYZING,
        SUCCESS,
        STOPPED,
        ERROR
    }
}