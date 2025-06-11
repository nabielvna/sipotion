package com.example.myapplication.data;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

public class UiState {

    @NonNull
    public final Status status;
    @Nullable
    public final String message;

    private UiState(@NonNull Status status, @Nullable String message) {
        this.status = status;
        this.message = message;
    }

    public static UiState ready() {
        return new UiState(Status.READY, "Ready to analyze");
    }

    public static UiState analyzing() {
        return new UiState(Status.ANALYZING, "Analyzing...");
    }

    public static UiState success(String posture, float confidence) {
        String resultText = "✓ " + posture + " (" + Math.round(confidence * 100) + "%)";
        return new UiState(Status.SUCCESS, resultText);
    }

    public static UiState stopped() {
        return new UiState(Status.STOPPED, "Analysis stopped");
    }

    public static UiState error(String errorMessage) {
        return new UiState(Status.ERROR, "Error: " + errorMessage);
    }

    public enum Status {
        READY,
        ANALYZING,
        SUCCESS,
        STOPPED,
        ERROR
    }
}