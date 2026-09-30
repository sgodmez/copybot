package com.copybot.engine.resume;

import com.google.gson.annotations.SerializedName;

public enum ResumeMode {
    @SerializedName("none") NONE,
    @SerializedName("state") STATE,
    @SerializedName("destination") DESTINATION,
    @SerializedName("stateThenDestination") STATE_THEN_DESTINATION
}
