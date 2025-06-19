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
import java.io.IOException;
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

    private static final String ROBOFLOW_BASE_URL = "https://detect.roboflow.com/sipotion-object-detection/8";
    private static final String ROBOFLOW_API_KEY = "zcZeM8rIczdRi00455rj";
    private static final int ROBOFLOW_CONFIDENCE_THRESHOLD = 60;

    private static final int BITMAP_COMPRESSION_QUALITY = 80;

    private final OkHttpClient client;
    private final Gson gson;
    private final ExecutorService roboflowExecutor;

    private final MutableLiveData<UiState> _uiState = new MutableLiveData<>(UiState.ready());
    public final LiveData<UiState> uiState = _uiState;

    private final AtomicBoolean isAnalyzing = new AtomicBoolean(false);
    private final AtomicBoolean isProcessingFrame = new AtomicBoolean(false);
    private boolean isCameraLive = true;

    private final ByteArrayOutputStream reusableBaos = new ByteArrayOutputStream();

    @Inject
    public MainViewModel(
            OkHttpClient client,
            Gson gson,
            @Named("roboflowExecutor") ExecutorService roboflowExecutor
    ) {
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
            List<Prediction> lastSuccess = _uiState.getValue() != null ? _uiState.getValue().lastSuccessfulPredictions : null;
            _uiState.postValue(UiState.analyzing(lastSuccess));
            Log.d(TAG, "Analysis started by user.");
        }
    }

    public void stopAnalysis() {
        if (isAnalyzing.compareAndSet(true, false)) {
            isProcessingFrame.set(false);
            _uiState.postValue(UiState.stopped());
            Log.d(TAG, "Analysis stopped by user.");
        }
    }

    public boolean isCurrentlyAnalyzing() {
        return isAnalyzing.get();
    }

    public boolean isCameraLive() {
        return isCameraLive;
    }

    public void setCameraLive(boolean cameraLive) {
        isCameraLive = cameraLive;
    }

    public void sendImageToRoboflow(Bitmap bitmap) {
        if (!isCurrentlyAnalyzing() && isCameraLive()) {
            if (bitmap != null && !bitmap.isRecycled()) bitmap.recycle();
            return;
        }

        if (!isProcessingFrame.compareAndSet(false, true)) {
            if (bitmap != null && !bitmap.isRecycled()) bitmap.recycle();
            Log.v(TAG, "Skipping frame, previous frame still processing.");
            return;
        }

        roboflowExecutor.submit(() -> {
            UiState currentState = _uiState.getValue();
            List<Prediction> lastPredictions = (currentState != null) ? currentState.lastSuccessfulPredictions : null;

            try {
                reusableBaos.reset();
                bitmap.compress(Bitmap.CompressFormat.JPEG, BITMAP_COMPRESSION_QUALITY, reusableBaos);
                byte[] byteArray = reusableBaos.toByteArray();
                String base64Image = Base64.encodeToString(byteArray, Base64.NO_WRAP);

                String url = ROBOFLOW_BASE_URL +
                        "?api_key=" + ROBOFLOW_API_KEY +
                        "&confidence=" + ROBOFLOW_CONFIDENCE_THRESHOLD;

                RequestBody requestBody = RequestBody.create(base64Image, MediaType.parse("application/x-www-form-urlencoded"));
                Request request = new Request.Builder().url(url).post(requestBody).build();

                try (Response response = client.newCall(request).execute()) {
                    if (!response.isSuccessful() || response.body() == null) {
                        throw new IOException("Detection Failed: " + response.code() + " " + response.message());
                    }

                    String responseBody = response.body().string();
                    RoboflowResponse roboflowResponse = gson.fromJson(responseBody, RoboflowResponse.class);

                    if (isAnalyzing.get() || !isCameraLive()) {
                        if (roboflowResponse != null && roboflowResponse.predictions != null && !roboflowResponse.predictions.isEmpty()) {
                            _uiState.postValue(UiState.success(roboflowResponse.predictions));
                        } else {
                            _uiState.postValue(UiState.noDetection(lastPredictions));
                        }
                    } else {
                        Log.d(TAG, "Skipping UI update for detection result as not in analyzing mode or not from gallery.");
                    }
                }

            } catch (Exception e) {
                Log.e(TAG, "An error occurred during Roboflow request: ", e);
                if (isAnalyzing.get() || !isCameraLive()) {
                    _uiState.postValue(UiState.error("Processing Error", lastPredictions));
                }
            } finally {
                // Pastikan bitmap yang dikirim ke Roboflow di-recycle setelah digunakan
                if (bitmap != null && !bitmap.isRecycled()) {
                    bitmap.recycle();
                }
                isProcessingFrame.set(false);
            }
        });
    }

    @Override
    protected void onCleared() {
        super.onCleared();
        roboflowExecutor.shutdown();
        try {
            reusableBaos.close();
        } catch (IOException e) {
            Log.e(TAG, "Error closing reusableBaos", e);
        }
    }

    public static class RoboflowResponse {
        public List<Prediction> predictions;
    }

    public static class Prediction {
        public float x;
        public float y;
        public float width;
        public float height;
        public float confidence;
        @SerializedName("class")
        public String className;
    }
}
