package com.example.android.sunshine.app;

/**
 * Created by knoxpo on 05/12/16.
 */

public class Constants {

    public static final class SP {
        private static final String CLASS_NAME = SP.class.getSimpleName();
        public static final String
                WEATHER_HIGH_I = CLASS_NAME+".WEATHER_HIGH_I",
                WEATHER_LOW_I = CLASS_NAME + ".WEATHER_LOW_I",
                WEATHER_ID_L = CLASS_NAME + ".WEATHER_ID_L";

    }

    public static final class Data{
        private static final String CLASS_NAME = Data.class.getSimpleName();

        public static final String
                PATH = "/path",
                WEATHER_ID = "weather_id",
                WEATHER_TEMP_HIGH = "weather_temp_high",
                WEATHER_TEMP_LOW = "weather_temp_low";

    }

}
