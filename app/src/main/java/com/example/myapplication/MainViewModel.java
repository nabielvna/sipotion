package com.example.myapplication;

import android.graphics.Bitmap;
import android.util.Base64;
import android.util.Log;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;

import com.example.myapplication.data.UiState;
import com.google.gson.Gson;
import com.google.gson.annotations.SerializedName;

import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.inject.Inject;
import javax.inject.Named;

import dagger.hilt.android.lifecycle.HiltViewModel;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

@HiltViewModel
public class MainViewModel extends ViewModel {
    private static final String TAG = "SITTING_POSTURE_VM";
    private static final String ROBOFLOW_API_URL = "https://detect.roboflow.com/sitting-posture-detection-3933f/1?api_key=sKe1oZrE1L1CzkUuJCaw";

    // Dependencies injected by Hilt
    private final OkHttpClient client;
    private final Gson gson;
    private final ExecutorService roboflowExecutor;

    // LiveData to communicate with the UI (Activity)
    private final MutableLiveData<UiState> _uiState = new MutableLiveData<>(UiState.ready());
    public final LiveData<UiState> uiState = _uiState;

    // Internal state to manage the analysis flow
    private final AtomicBoolean isAnalyzing = new AtomicBoolean(false);

    @Inject
    public MainViewModel(OkHttpClient client, Gson gson, @Named("roboflowExecutor") ExecutorService roboflowExecutor) {
        this.client = client;
        this.gson = gson;
        this.roboflowExecutor = roboflowExecutor;
    }

    public void toggleAnalysis() {
        if (isAnalyzing.get()) {
            stopAnalysis();
        } else {
            startAnalysis();
        }
    }

    public void startAnalysis() {
        if (isAnalyzing.compareAndSet(false, true)) {
            _uiState.postValue(UiState.analyzing());
            Log.d(TAG, "Analysis started by user.");
        }
    }

    public void stopAnalysis() {
        if (isAnalyzing.compareAndSet(true, false)) {
            _uiState.postValue(UiState.stopped());
            Log.d(TAG, "Analysis stopped by user.");
        }
    }

    public boolean isCurrentlyAnalyzing() {
        return isAnalyzing.get();
    }

    public void sendImageToRoboflow(Bitmap bitmap) {
        if (!isAnalyzing.get()) {
            if (bitmap != null && !bitmap.isRecycled()) bitmap.recycle();
            return;
        }

        roboflowExecutor.submit(() -> {
            try {
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                bitmap.compress(Bitmap.CompressFormat.JPEG, 90, baos);
                byte[] byteArray = baos.toByteArray();
                String base64Image = Base64.encodeToString(byteArray, Base64.NO_WRAP);

                RequestBody requestBody = RequestBody.create(base64Image, MediaType.parse("application/x-www-form-urlencoded"));
                Request request = new Request.Builder().url(ROBOFLOW_API_URL).post(requestBody).build();

                try (Response response = client.newCall(request).execute()) {
                    if (!response.isSuccessful() || response.body() == null) {
                        throw new Exception("Detection Failed: " + response.code());
                    }

                    String responseBody = response.body().string();
                    RoboflowResponse roboflowResponse = gson.fromJson(responseBody, RoboflowResponse.class);

                    // --- MODIFICATION START ---
                    // Don't stop analysis on success. Just post the latest result.
                    if (roboflowResponse != null && roboflowResponse.predictions != null && !roboflowResponse.predictions.isEmpty()) {
                        Prediction first = roboflowResponse.predictions.get(0);
                        _uiState.postValue(UiState.success(first.className, first.confidence));
                        Log.d(TAG, "Posture updated: " + first.className);
                    } else {
                        // If no object is detected, we can post the "Analyzing..." status again
                        // to let the user know it's still working.
                        _uiState.postValue(UiState.analyzing());
                    }
                    // --- MODIFICATION END ---
                }

            } catch (Exception e) {
                // --- MODIFICATION START ---
                // Don't stop analysis on error. Just post the error message.
                // The analysis will continue with the next frame.
                Log.e(TAG, "Error sending image: ", e);
                _uiState.postValue(UiState.error(e.getMessage()));
                // --- MODIFICATION END ---
            } finally {
                if (bitmap != null && !bitmap.isRecycled()) {
                    bitmap.recycle();
                }
            }
        });
    }

    @Override
    protected void onCleared() {
        super.onCleared();
        roboflowExecutor.shutdown();
    }

    // --- Data Classes for JSON Response ---
    public static class RoboflowResponse { public List<Prediction> predictions; }
    public static class Prediction { public float confidence; @SerializedName("class") public String className; }
}