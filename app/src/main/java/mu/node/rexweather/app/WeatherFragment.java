package mu.node.rexweather.app;

import android.content.Context;
import android.location.Location;
import android.location.LocationManager;
import android.os.Bundle;
import android.support.v4.app.Fragment;
import android.support.v4.widget.SwipeRefreshLayout;
import android.support.v7.widget.LinearLayoutManager;
import android.support.v7.widget.RecyclerView;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import org.apache.http.HttpException;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import butterknife.Bind;
import butterknife.ButterKnife;
import de.keyboardsurfer.android.widget.crouton.Crouton;
import de.keyboardsurfer.android.widget.crouton.Style;
import mu.node.rexweather.app.Helpers.DayFormatter;
import mu.node.rexweather.app.Helpers.DividerItemDecoration;
import mu.node.rexweather.app.Helpers.TemperatureFormatter;
import mu.node.rexweather.app.Models.CurrentWeather;
import mu.node.rexweather.app.Models.WeatherForecast;
import mu.node.rexweather.app.Services.LocationService;
import mu.node.rexweather.app.Services.WeatherService;
import retrofit.RetrofitError;
import rx.Observable;
import rx.Subscriber;
import rx.android.schedulers.AndroidSchedulers;
import rx.functions.Func1;
import rx.functions.Func2;
import rx.schedulers.Schedulers;
import rx.subscriptions.CompositeSubscription;

/**
 * Weather Fragment.
 * <p/>
 * Displays the current weather as well as a 7 day forecast for our location. Data is loaded
 * from a web service.
 */
public class WeatherFragment extends Fragment {

    private static final int WEATHER_HEADER = 0;
    private static final int WEATHER_ITEM   = 1;

    private static final String KEY_CURRENT_WEATHER      = "key_current_weather";
    private static final String KEY_WEATHER_FORECASTS    = "key_weather_forecasts";
    private static final long   LOCATION_TIMEOUT_SECONDS = 20;
    private static final String TAG                      = WeatherFragment.class.getCanonicalName();

    @Bind(R.id.swipe_refresh_container)
    SwipeRefreshLayout mSwipeRefreshLayout;
    @Bind(R.id.weather_forecast_list)
    RecyclerView       mForecastRecyclerView;
    @Bind(R.id.attribution)
    TextView           mAttributionTextView;

    private WeatherForecastListAdapter mAdapter;
    private CompositeSubscription mCompositeSubscription;

    @Override
    public View onCreateView(final LayoutInflater inflater, final ViewGroup container,
                             final Bundle savedInstanceState) {
        mCompositeSubscription = new CompositeSubscription();
        final View rootView = inflater.inflate(R.layout.fragment_weather, container, false);
        ButterKnife.bind(this, rootView);

        // Set up list view for weather forecasts.
        mAdapter = new WeatherForecastListAdapter();
        mForecastRecyclerView.addItemDecoration(new DividerItemDecoration(WeatherApplication.get(),
                                                                          DividerItemDecoration.VERTICAL_LIST));
        mForecastRecyclerView.setHasFixedSize(true);
        final RecyclerView.LayoutManager layoutManager = new LinearLayoutManager(WeatherApplication.get());
        mForecastRecyclerView.setLayoutManager(layoutManager);
        mForecastRecyclerView.setAdapter(mAdapter);

        mAttributionTextView.setVisibility(View.INVISIBLE);

        // Set up swipe refresh layout.
        mSwipeRefreshLayout.setColorSchemeResources(R.color.brand_main,
                                                    android.R.color.black,
                                                    R.color.brand_main,
                                                    android.R.color.black);
        mSwipeRefreshLayout.setOnRefreshListener(new SwipeRefreshLayout.OnRefreshListener() {
            @Override
            public void onRefresh() {
                updateWeather();
            }
        });

        updateWeather();

        return rootView;
    }

    @Override
    public void onDestroyView() {
        mCompositeSubscription.unsubscribe();
        super.onDestroyView();
        ButterKnife.unbind(this);
    }

    /**
     * Provides items for our list view.
     */
    public class WeatherForecastListAdapter extends RecyclerView.Adapter<WeatherForecastListAdapter.ViewHolder> {

        private List<WeatherForecast> mWeatherForecasts;
        private CurrentWeather        mCurrentWeather;

        public WeatherForecastListAdapter() {
            this.mWeatherForecasts = new ArrayList<WeatherForecast>();
        }

        public void updateData(List<WeatherForecast> forecasts) {
            mWeatherForecasts = forecasts;
            notifyDataSetChanged();
        }

        public void updateCurrentWeather(CurrentWeather currentWeather) {
            mCurrentWeather = currentWeather;
            notifyDataSetChanged();
        }

        @Override
        public int getItemViewType(int position) {
            if (mCurrentWeather != null && position == 0) {
                return WEATHER_HEADER;
            } else {
                return WEATHER_ITEM;
            }
        }

        @Override
        public ViewHolder onCreateViewHolder(ViewGroup viewGroup, int viewType) {
            switch (viewType) {
                case WEATHER_HEADER:
                    View header = LayoutInflater.from(viewGroup.getContext())
                                                .inflate(R.layout.weather_forecast_list_header,
                                                         viewGroup,
                                                         false);
                    return new HeaderViewHolder(header);
                default:
                    View item = LayoutInflater.from(viewGroup.getContext())
                                              .inflate(R.layout.weather_forecast_list_item,
                                                       viewGroup,
                                                       false);
                    return new ItemViewHolder(item);
            }
        }

        @Override
        public void onBindViewHolder(ViewHolder viewHolder, int i) {
            viewHolder.configure(i);
        }

        @Override
        public int getItemCount() {
            return mWeatherForecasts.size() + (mCurrentWeather != null ? 1 : 0);
        }

        public class ItemViewHolder extends ViewHolder {

            @Bind(R.id.day)
            TextView dayTextView;
            @Bind(R.id.description)
            TextView descriptionTextView;
            @Bind(R.id.maximum_temperature)
            TextView maximumTemperatureTextView;
            @Bind(R.id.minimum_temperature)
            TextView minimumTemperatureTextView;

            public ItemViewHolder(View itemView) {
                super(itemView);
                ButterKnife.bind(this, itemView);
            }

            @Override
            public void configure(int position) {
                final WeatherForecast weatherForecast = mWeatherForecasts.get(position - (mCurrentWeather != null ? 1 : 0));
                final DayFormatter dayFormatter = new DayFormatter(getActivity());
                final String day = dayFormatter.format(weatherForecast.getTimestamp());
                dayTextView.setText(day);
                descriptionTextView.setText(weatherForecast.getDescription());
                maximumTemperatureTextView.setText(
                        TemperatureFormatter.format(weatherForecast.getMaximumTemperature()));
                minimumTemperatureTextView.setText(
                        TemperatureFormatter.format(weatherForecast.getMinimumTemperature()));
            }
        }

        public class HeaderViewHolder extends ViewHolder {

            @Bind(R.id.location_name)
            TextView locationName;
            @Bind(R.id.current_temperature)
            TextView currentTemp;

            public HeaderViewHolder(View itemView) {
                super(itemView);
                ButterKnife.bind(this, itemView);
            }

            @Override
            public void configure(int position) {
                locationName.setText(mCurrentWeather.getLocationName());
                currentTemp.setText(
                        TemperatureFormatter.format(mCurrentWeather.getTemperature()));
            }
        }

        public abstract class ViewHolder extends RecyclerView.ViewHolder {

            public ViewHolder(View itemView) {
                super(itemView);
            }

            public abstract void configure(int position);
        }
    }

    /**
     * Get weather data for the current location and update the UI.
     */
    private void updateWeather() {
        mSwipeRefreshLayout.setRefreshing(true);

        final LocationManager locationManager = (LocationManager) getActivity()
                .getSystemService(Context.LOCATION_SERVICE);
        final LocationService locationService = new LocationService(locationManager);

        // Get our current location.
        final Observable<HashMap<String, WeatherForecast>> fetchDataObservable = locationService.getLocation()
                                                                                                .timeout(
                                                                                                        LOCATION_TIMEOUT_SECONDS,
                                                                                                        TimeUnit.SECONDS)
                                                                                                .flatMap(
                                                                                                        new Func1<Location, Observable<HashMap<String, WeatherForecast>>>() {
                                                                                                            @Override
                                                                                                            public Observable<HashMap<String, WeatherForecast>> call(
                                                                                                                    final Location location) {
                                                                                                                final WeatherService weatherService = new WeatherService();
                                                                                                                final double longitude = location.getLongitude();
                                                                                                                final double latitude = location.getLatitude();

                                                                                                                return Observable.zip(
                                                                                                                        // Fetch current and 7 day forecasts for the location.
                                                                                                                        weatherService.fetchCurrentWeather(
                                                                                                                                longitude,
                                                                                                                                latitude),
                                                                                                                        weatherService.fetchWeatherForecasts(
                                                                                                                                longitude,
                                                                                                                                latitude),

                                                                                                                        // Only handle the fetched results when both sets are available.
                                                                                                                        new Func2<CurrentWeather, List<WeatherForecast>,
                                                                                                                                HashMap<String, WeatherForecast>>() {
                                                                                                                            @Override
                                                                                                                            public HashMap call(
                                                                                                                                    final CurrentWeather currentWeather,
                                                                                                                                    final List<WeatherForecast> weatherForecasts) {

                                                                                                                                HashMap<String, Object> weatherData = new HashMap<String, Object>();
                                                                                                                                weatherData.put(
                                                                                                                                        KEY_CURRENT_WEATHER,
                                                                                                                                        currentWeather);
                                                                                                                                weatherData.put(
                                                                                                                                        KEY_WEATHER_FORECASTS,
                                                                                                                                        weatherForecasts);
                                                                                                                                return weatherData;
                                                                                                                            }
                                                                                                                        }
                                                                                                                                     );
                                                                                                            }
                                                                                                        });

        mCompositeSubscription.add(fetchDataObservable
                                           .subscribeOn(Schedulers.newThread())
                                           .observeOn(AndroidSchedulers.mainThread())
                                           .subscribe(new Subscriber<HashMap<String, WeatherForecast>>() {
                                               @Override
                                               public void onNext(final HashMap<String, WeatherForecast> weatherData) {

                                                   // Update UI with current weather.
                                                   final CurrentWeather currentWeather = (CurrentWeather) weatherData
                                                           .get(KEY_CURRENT_WEATHER);
                                                   mAdapter.updateCurrentWeather(currentWeather);

                                                   // Update weather forecast list.
                                                   @SuppressWarnings("unchecked") final List<WeatherForecast> weatherForecasts = (List<WeatherForecast>)
                                                           weatherData.get(KEY_WEATHER_FORECASTS);
                                                   mAdapter.updateData(weatherForecasts);
                                               }

                                               @Override
                                               public void onCompleted() {
                                                   mSwipeRefreshLayout.setRefreshing(false);
                                                   mAttributionTextView.setVisibility(View.VISIBLE);
                                               }

                                               @Override
                                               public void onError(final Throwable error) {
                                                   mSwipeRefreshLayout.setRefreshing(false);

                                                   if (error instanceof TimeoutException) {
                                                       Crouton.makeText(getActivity(),
                                                                        R.string.error_location_unavailable,
                                                                        Style.ALERT)
                                                              .show();
                                                   } else if (error instanceof RetrofitError
                                                              || error instanceof HttpException) {
                                                       Crouton.makeText(getActivity(),
                                                                        R.string.error_fetch_weather,
                                                                        Style.ALERT)
                                                              .show();
                                                   } else {
                                                       Log.e(TAG, error.getMessage());
                                                       error.printStackTrace();
                                                       throw new RuntimeException(
                                                               "See inner exception");
                                                   }
                                               }
                                           })
                                  );
    }
}
