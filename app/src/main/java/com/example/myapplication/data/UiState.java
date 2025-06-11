package com.example.myapplication.data;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.example.myapplication.MainViewModel;

public class UiState {

    @NonNull
    public final Status status;
    @Nullable
    public final String message;
    @Nullable
    public final MainViewModel.Prediction prediction; // Field untuk membawa data prediksi

    // Ubah constructor untuk menerima objek Prediction
    private UiState(@NonNull Status status, @Nullable String message, @Nullable MainViewModel.Prediction prediction) {
        this.status = status;
        this.message = message;
        this.prediction = prediction;
    }

    // Perbarui factory methods
    public static UiState ready() {
        return new UiState(Status.READY, "Ready to analyze", null);
    }

    public static UiState analyzing() {
        return new UiState(Status.ANALYZING, "Analyzing...", null);
    }

    // Method success sekarang menerima seluruh objek Prediction
    public static UiState success(MainViewModel.Prediction prediction) {
        String resultText = "✓ " + prediction.className + " (" + Math.round(prediction.confidence * 100) + "%)";
        return new UiState(Status.SUCCESS, resultText, prediction);
    }

    public static UiState stopped() {
        return new UiState(Status.STOPPED, "Analysis stopped", null);
    }

    public static UiState error(String errorMessage) {
        return new UiState(Status.ERROR, "Error: " + errorMessage, null);
    }

    // Method ini digunakan jika Roboflow tidak menemukan objek apa pun
    public static UiState noDetection() {
        return new UiState(Status.ANALYZING, "Analyzing... (No object detected)", null);
    }


    public enum Status {
        READY,
        ANALYZING,
        SUCCESS,
        STOPPED,
        ERROR
    }
}
