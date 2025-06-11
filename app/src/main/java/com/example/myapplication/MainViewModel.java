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

    private static final String ROBOFLOW_BASE_URL = "https://detect.roboflow.com/sitting-posture-detection-3933f/1";
    private static final String ROBOFLOW_API_KEY = "sKe1oZrE1L1CzkUuJCaw";
    private static final int BITMAP_COMPRESSION_QUALITY = 90;

    private final OkHttpClient client;
    private final Gson gson;
    private final ExecutorService roboflowExecutor;
    private final ImageSaver imageSaver;
    private final ExecutorService imageSaverExecutor;

    private final MutableLiveData<UiState> _uiState = new MutableLiveData<>(UiState.ready());
    public final LiveData<UiState> uiState = _uiState;

    private final AtomicBoolean isAnalyzing = new AtomicBoolean(false);
    private final AtomicBoolean isProcessingFrame = new AtomicBoolean(false);


    @Inject
    public MainViewModel(
            OkHttpClient client,
            Gson gson,
            @Named("roboflowExecutor") ExecutorService roboflowExecutor,
            @Named("imageSaverExecutor") ExecutorService imageSaverExecutor,
            ImageSaver imageSaver
    ) {
        this.client = client;
        this.gson = gson;
        this.roboflowExecutor = roboflowExecutor;
        this.imageSaver = imageSaver;
        this.imageSaverExecutor = imageSaverExecutor;
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
            isProcessingFrame.set(false);
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

        if (!isProcessingFrame.compareAndSet(false, true)) {
            Log.v(TAG, "Skipping frame, previous frame still processing.");
            if (bitmap != null && !bitmap.isRecycled()) {
                bitmap.recycle();
            }
            return;
        }

        roboflowExecutor.submit(() -> {
            if (!isAnalyzing.get()) {
                Log.d(TAG, "Aborting frame processing because analysis has been stopped.");
                if (bitmap != null && !bitmap.isRecycled()) {
                    bitmap.recycle();
                }
                isProcessingFrame.set(false);
                return;
            }

            boolean bitmapPassedToSaver = false;
            try {
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                bitmap.compress(Bitmap.CompressFormat.JPEG, BITMAP_COMPRESSION_QUALITY, baos);
                byte[] byteArray = baos.toByteArray();
                String base64Image = Base64.encodeToString(byteArray, Base64.NO_WRAP);

                String url = ROBOFLOW_BASE_URL + "?api_key=" + ROBOFLOW_API_KEY;
                RequestBody requestBody = RequestBody.create(base64Image, MediaType.parse("application/x-www-form-urlencoded"));
                Request request = new Request.Builder().url(url).post(requestBody).build();

                try (Response response = client.newCall(request).execute()) {
                    if (!response.isSuccessful() || response.body() == null) {
                        throw new IOException("Detection Failed: " + response.code() + " " + response.message());
                    }

                    String responseBody = response.body().string();
                    RoboflowResponse roboflowResponse = gson.fromJson(responseBody, RoboflowResponse.class);

                    // --- PERBAIKAN UTAMA: Tambahkan pengecekan terakhir di sini ---
                    // Sebelum menampilkan hasil, pastikan kita masih dalam mode analisis.
                    if (isAnalyzing.get()) {
                        if (roboflowResponse != null && roboflowResponse.predictions != null && !roboflowResponse.predictions.isEmpty()) {
                            Prediction first = roboflowResponse.predictions.get(0);

                            _uiState.postValue(UiState.success(first));
                            Log.d(TAG, "Posture updated: " + first.className + " with confidence " + first.confidence);

                            final Bitmap bitmapToSave = bitmap;
                            imageSaverExecutor.submit(() -> {
                                imageSaver.saveBitmap(bitmapToSave, System.currentTimeMillis(), first.className, first.confidence);
                            });
                            bitmapPassedToSaver = true;

                        } else {
                            _uiState.postValue(UiState.noDetection());
                        }
                    } else {
                        Log.d(TAG, "Analysis stopped. Ignoring result from in-flight request.");
                    }
                }

            } catch (IOException e) {
                Log.e(TAG, "Network or Server Error: ", e);
                // Hanya update UI jika masih menganalisis
                if(isAnalyzing.get()) _uiState.postValue(UiState.error("Network Error"));
            } catch (Exception e) {
                Log.e(TAG, "An unexpected error occurred: ", e);
                if(isAnalyzing.get()) _uiState.postValue(UiState.error(e.getMessage()));
            } finally {
                if (!bitmapPassedToSaver && bitmap != null && !bitmap.isRecycled()) {
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
        imageSaverExecutor.shutdown();
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
