package mu.node.rexweather.app;

import android.app.Application;

public class WeatherApplication extends Application {

    private static WeatherApplication mInstance;

    @Override
    public void onCreate() {
        super.onCreate();
        mInstance = this;
    }

    public static WeatherApplication get() {
        return mInstance;
    }
}
