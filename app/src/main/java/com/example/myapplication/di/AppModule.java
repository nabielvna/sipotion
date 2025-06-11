package com.example.myapplication.di;

import com.google.gson.Gson;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import javax.inject.Named;
import javax.inject.Singleton;
import dagger.Module;
import dagger.Provides;
import dagger.hilt.InstallIn;
import dagger.hilt.components.SingletonComponent;
import okhttp3.OkHttpClient;

@Module
@InstallIn(SingletonComponent.class)
public class AppModule {

    @Provides
    @Singleton
    public OkHttpClient provideOkHttpClient() {
        return new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS) // Batas waktu koneksi
                .readTimeout(30, TimeUnit.SECONDS)    // Batas waktu membaca data
                .writeTimeout(30, TimeUnit.SECONDS)   // Batas waktu menulis data
                .build();
    }

    @Provides
    @Singleton
    public Gson provideGson() {
        return new Gson();
    }

    @Provides
    @Singleton
    @Named("cameraExecutor")
    public ExecutorService provideCameraExecutor() {
        return Executors.newSingleThreadExecutor();
    }

    @Provides
    @Singleton
    @Named("roboflowExecutor")
    public ExecutorService provideRoboflowExecutor() {
        return Executors.newSingleThreadExecutor();
    }

    // --- PENAMBAHAN: Executor khusus untuk menyimpan gambar ---
    @Provides
    @Singleton
    @Named("imageSaverExecutor")
    public ExecutorService provideImageSaverExecutor() {
        return Executors.newSingleThreadExecutor();
    }
}
