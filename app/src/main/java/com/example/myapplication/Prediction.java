package com.example.myapplication;

import com.google.gson.annotations.SerializedName;

public class Prediction {
    // Map the JSON key "class" to this Java field
    @SerializedName("class")
    public String className;

    public float confidence;

    // (Optional) If you ever want x, y, width, height:
    // public float x;
    // public float y;
    // public float width;
    // public float height;
    //
    // If you add them, Gson will automatically fill those too.
}
