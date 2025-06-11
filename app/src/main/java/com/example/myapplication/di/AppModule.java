package com.example.myapplication.di;

import com.google.gson.Gson;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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
        return new OkHttpClient();
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
}