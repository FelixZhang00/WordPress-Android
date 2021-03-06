package org.wordpress.android.ui.stats;

import android.app.Activity;
import android.app.FragmentManager;
import android.app.FragmentTransaction;
import android.os.Bundle;
import android.os.Handler;
import android.support.v7.app.ActionBar;
import android.support.v7.app.ActionBarActivity;
import android.view.MenuItem;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import com.android.volley.VolleyError;
import com.wordpress.rest.RestRequest;

import org.json.JSONException;
import org.json.JSONObject;
import org.wordpress.android.R;
import org.wordpress.android.WordPress;
import org.wordpress.android.analytics.AnalyticsTracker;
import org.wordpress.android.networking.RestClientUtils;
import org.wordpress.android.ui.stats.service.StatsService;
import org.wordpress.android.util.AppLog;
import org.wordpress.android.util.NetworkUtils;
import org.wordpress.android.util.ToastUtils;
import org.wordpress.android.util.helpers.SwipeToRefreshHelper;
import org.wordpress.android.util.widgets.CustomSwipeRefreshLayout;

import java.io.Serializable;
import java.lang.ref.WeakReference;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;

import de.greenrobot.event.EventBus;

/**
 *  Single item details activity.
 */
public class StatsViewAllActivity extends ActionBarActivity
        implements StatsAbstractListFragment.OnRequestDataListener,
        StatsAbstractFragment.TimeframeDateProvider {

    private boolean mIsInFront;
    private boolean mIsUpdatingStats;
    private SwipeToRefreshHelper mSwipeToRefreshHelper;

    private final Handler mHandler = new Handler();

    private StatsAbstractListFragment mFragment;

    private int mLocalBlogID = -1;
    private StatsTimeframe mTimeframe;
    private StatsViewType mStatsViewType;
    private String mDate;
    private Serializable[] mRestResponse;
    private int mOuterPagerSelectedButtonIndex = 0;

    // The number of results to return per page for Paged REST endpoints. Numbers larger than 20 will default to 20 on the server.
    public static final int MAX_RESULTS_PER_PAGE = 20;

    // The number of results to return for NON Paged REST endpoints.
    private static final int MAX_RESULTS_REQUESTED = 100;


    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.stats_activity_view_all);

        if (savedInstanceState == null) {
            AnalyticsTracker.track(AnalyticsTracker.Stat.STATS_VIEW_ALL_ACCESSED);
        }

        ActionBar actionBar = getSupportActionBar();
        if (actionBar != null) {
            actionBar.setDisplayHomeAsUpEnabled(true);
        }

        // pull to refresh setup
        mSwipeToRefreshHelper = new SwipeToRefreshHelper(this, (CustomSwipeRefreshLayout) findViewById(R.id.ptr_layout),
                new SwipeToRefreshHelper.RefreshListener() {
                    @Override
                    public void onRefreshStarted() {
                        if (!NetworkUtils.checkConnection(getBaseContext())) {
                            mSwipeToRefreshHelper.setRefreshing(false);
                            return;
                        }
                        refreshStats();
                    }
                }
        );

        if (savedInstanceState != null) {
            mLocalBlogID = savedInstanceState.getInt(StatsActivity.ARG_LOCAL_TABLE_BLOG_ID, -1);
            Serializable oldData = savedInstanceState.getSerializable(StatsAbstractFragment.ARG_REST_RESPONSE);
            if (oldData != null && oldData instanceof Serializable[]) {
                mRestResponse = (Serializable[]) oldData;
            }
            mTimeframe = (StatsTimeframe) savedInstanceState.getSerializable(StatsAbstractFragment.ARGS_TIMEFRAME);
            mDate = savedInstanceState.getString(StatsAbstractFragment.ARGS_START_DATE);
            mStatsViewType = (StatsViewType) savedInstanceState.getSerializable(StatsAbstractFragment.ARGS_VIEW_TYPE);
            mOuterPagerSelectedButtonIndex = savedInstanceState.getInt(StatsAbstractListFragment.ARGS_TOP_PAGER_SELECTED_BUTTON_INDEX, 0);
        } else if (getIntent() != null) {
            Bundle extras = getIntent().getExtras();
            mLocalBlogID = extras.getInt(StatsActivity.ARG_LOCAL_TABLE_BLOG_ID, -1);
            mTimeframe = (StatsTimeframe) extras.getSerializable(StatsAbstractFragment.ARGS_TIMEFRAME);
            mDate = extras.getString(StatsAbstractFragment.ARGS_START_DATE);
            mStatsViewType = (StatsViewType) extras.getSerializable(StatsAbstractFragment.ARGS_VIEW_TYPE);
            mOuterPagerSelectedButtonIndex = extras.getInt(StatsAbstractListFragment.ARGS_TOP_PAGER_SELECTED_BUTTON_INDEX, 0);
        }

        if (mStatsViewType == null || mTimeframe == null || mDate == null) {
            Toast.makeText(this, getResources().getText(R.string.stats_generic_error),
                    Toast.LENGTH_SHORT).show();
            finish();
        }

        // Setup the top date label. It's available on those fragments that are affected by the top date selector.
        TextView dateTextView = (TextView) findViewById(R.id.stats_summary_date);
        switch (mStatsViewType) {
            case TOP_POSTS_AND_PAGES:
            case REFERRERS:
            case CLICKS:
            case GEOVIEWS:
            case AUTHORS:
            case VIDEO_PLAYS:
            case SEARCH_TERMS:
                dateTextView.setText(getDateForDisplayInLabels(mDate, mTimeframe));
                dateTextView.setVisibility(View.VISIBLE);
                break;
            default:
                dateTextView.setVisibility(View.GONE);
                break;
        }

        setTitle(R.string.stats);

        FragmentManager fm = getFragmentManager();
        FragmentTransaction ft = fm.beginTransaction();
        mFragment = (StatsAbstractListFragment) fm.findFragmentByTag("ViewAll-Fragment");
        if (mFragment == null) {
            mFragment = getInnerFragment();
            ft.replace(R.id.stats_single_view_fragment, mFragment, "ViewAll-Fragment");
            ft.commitAllowingStateLoss();
        }
    }

    private String getDateForDisplayInLabels(String date, StatsTimeframe timeframe) {
        String prefix = getString(R.string.stats_for);
        switch (timeframe) {
            case DAY:
                return String.format(prefix, StatsUtils.parseDate(date, StatsConstants.STATS_INPUT_DATE_FORMAT,
                        StatsConstants.STATS_OUTPUT_DATE_MONTH_LONG_DAY_SHORT_FORMAT));
            case WEEK:
                try {
                    SimpleDateFormat sdf = new SimpleDateFormat(StatsConstants.STATS_INPUT_DATE_FORMAT);
                    final Date parsedDate = sdf.parse(date);
                    Calendar c = Calendar.getInstance();
                    c.setTime(parsedDate);
                    String  endDateLabel = StatsUtils.msToString(c.getTimeInMillis(), StatsConstants.STATS_OUTPUT_DATE_MONTH_LONG_DAY_LONG_FORMAT);
                    // last day of this week
                    c.add(Calendar.DAY_OF_WEEK, - 6);
                    String startDateLabel = StatsUtils.msToString(c.getTimeInMillis(), StatsConstants.STATS_OUTPUT_DATE_MONTH_LONG_DAY_LONG_FORMAT);
                    return String.format(prefix,  startDateLabel + " - " + endDateLabel);
                } catch (ParseException e) {
                    AppLog.e(AppLog.T.UTILS, e);
                    return "";
                }
            case MONTH:
                return String.format(prefix, StatsUtils.parseDate(date, StatsConstants.STATS_INPUT_DATE_FORMAT, StatsConstants.STATS_OUTPUT_DATE_MONTH_LONG_FORMAT));
            case YEAR:
                return String.format(prefix, StatsUtils.parseDate(date, StatsConstants.STATS_INPUT_DATE_FORMAT, StatsConstants.STATS_OUTPUT_DATE_YEAR_FORMAT));
        }
        return "";
    }

    private StatsAbstractListFragment getInnerFragment() {
        StatsAbstractListFragment fragment = null;
        switch (mStatsViewType) {
            case TOP_POSTS_AND_PAGES:
                fragment = new StatsTopPostsAndPagesFragment();
                break;
            case REFERRERS:
                fragment = new StatsReferrersFragment();
                break;
            case CLICKS:
                fragment = new StatsClicksFragment();
                break;
            case GEOVIEWS:
                fragment = new StatsGeoviewsFragment();
                break;
            case AUTHORS:
                fragment = new StatsAuthorsFragment();
                break;
            case VIDEO_PLAYS:
                fragment = new StatsVideoplaysFragment();
                break;
            case COMMENTS:
                fragment = new StatsCommentsFragment();
                break;
            case TAGS_AND_CATEGORIES:
                fragment = new StatsTagsAndCategoriesFragment();
                break;
            case PUBLICIZE:
                fragment = new StatsPublicizeFragment();
                break;
            case FOLLOWERS:
                fragment = new StatsFollowersFragment();
                break;
            case SEARCH_TERMS:
                fragment = new StatsSearchTermsFragment();
                break;
        }

        Bundle args = new Bundle();
        args.putInt(StatsActivity.ARG_LOCAL_TABLE_BLOG_ID, mLocalBlogID);
        args.putSerializable(StatsAbstractFragment.ARGS_VIEW_TYPE, mStatsViewType);
        args.putSerializable(StatsAbstractFragment.ARGS_TIMEFRAME, mTimeframe);
        args.putBoolean(StatsAbstractListFragment.ARGS_IS_SINGLE_VIEW, true); // Always true here
        args.putString(StatsAbstractFragment.ARGS_START_DATE, mDate);
        args.putInt(StatsAbstractListFragment.ARGS_TOP_PAGER_SELECTED_BUTTON_INDEX, mOuterPagerSelectedButtonIndex);
        args.putSerializable(StatsAbstractFragment.ARG_REST_RESPONSE, mRestResponse);
        fragment.setArguments(args);
        return fragment;
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        outState.putInt(StatsActivity.ARG_LOCAL_TABLE_BLOG_ID, mLocalBlogID);
        outState.putSerializable(StatsAbstractFragment.ARG_REST_RESPONSE, mRestResponse);
        outState.putSerializable(StatsAbstractFragment.ARGS_TIMEFRAME, mTimeframe);
        outState.putString(StatsAbstractFragment.ARGS_START_DATE, mDate);
        outState.putSerializable(StatsAbstractFragment.ARGS_VIEW_TYPE, mStatsViewType);
        outState.putInt(StatsAbstractListFragment.ARGS_TOP_PAGER_SELECTED_BUTTON_INDEX, mOuterPagerSelectedButtonIndex);
        super.onSaveInstanceState(outState);
    }

    @Override
    protected void onResume() {
        super.onResume();
        mIsInFront = true;
    }

    @Override
    protected void onPause() {
        super.onPause();
        mIsInFront = false;
        mIsUpdatingStats = false;
        mSwipeToRefreshHelper.setRefreshing(false);
    }

    @Override
    public boolean onOptionsItemSelected(final MenuItem item) {
        switch (item.getItemId()) {
            case android.R.id.home:
                onBackPressed();
                return true;
            default:
                return super.onOptionsItemSelected(item);
        }
    }

    private String getRestPath(StatsService.StatsEndpointsEnum restEndpoint, int pageNumber) {
        final String blogId = StatsUtils.getBlogId(mLocalBlogID);
        String endpointPath = "";
        switch (restEndpoint) {
            case TOP_POSTS:
                endpointPath = "top-posts";
                break;
            case REFERRERS:
                endpointPath = "referrers";
                break;
            case CLICKS:
                endpointPath = "clicks";
                break;
            case GEO_VIEWS:
                endpointPath = "country-views";
                break;
            case AUTHORS:
                endpointPath = "top-authors";
                break;
            case VIDEO_PLAYS:
                endpointPath = "video-plays";
                break;
            case COMMENTS:
                endpointPath = "comments";
                break;
            case TAGS_AND_CATEGORIES:
                endpointPath = "tags";
                break;
            case PUBLICIZE:
                endpointPath = "publicize";
                break;
            case SEARCH_TERMS:
                endpointPath = "search-terms";
                break;
            case FOLLOWERS_WPCOM:
                endpointPath = "followers";
                return String.format("/sites/%s/stats/%s?type=wpcom&period=%s&date=%s&max=%s&page=%s", blogId, endpointPath,
                        mTimeframe.getLabelForRestCall(), mDate, MAX_RESULTS_PER_PAGE, pageNumber);
            case FOLLOWERS_EMAIL:
                endpointPath = "followers";
                return String.format("/sites/%s/stats/%s?type=email&period=%s&date=%s&max=%s&page=%s", blogId, endpointPath,
                        mTimeframe.getLabelForRestCall(), mDate, MAX_RESULTS_PER_PAGE, pageNumber);
            case COMMENT_FOLLOWERS:
                endpointPath = "comment-followers";
                return String.format("/sites/%s/stats/%s?period=%s&date=%s&max=%s&page=%s", blogId, endpointPath,
                    mTimeframe.getLabelForRestCall(), mDate, MAX_RESULTS_PER_PAGE, 1);
        }

        // All other endpoints returns 100 items in details view
        return String.format("/sites/%s/stats/%s?period=%s&date=%s&max=%s", blogId, endpointPath,
                mTimeframe.getLabelForRestCall(), mDate, MAX_RESULTS_REQUESTED);
    }

    private void refreshStats() {
        if (mIsUpdatingStats) {
            return;
        }

        if (!NetworkUtils.isNetworkAvailable(this)) {
            mSwipeToRefreshHelper.setRefreshing(false);
            AppLog.w(AppLog.T.STATS, "ViewAll on "+ mFragment.getTag() + " > no connection, update canceled");
            return;
        }

        mSwipeToRefreshHelper.setRefreshing(true);
        mIsUpdatingStats = true;
        final RestClientUtils restClientUtils = WordPress.getRestClientUtilsV1_1();
        final String blogId = StatsUtils.getBlogId(mLocalBlogID);
        StatsService.StatsEndpointsEnum[] sections = mFragment.getSectionsToUpdate();
        for (int i = 0; i < sections.length; i++) {
            StatsService.StatsEndpointsEnum currentSection = sections[i];
            String singlePostRestPath = getRestPath(currentSection, 1);
            RestListener vListener = new RestListener(this, currentSection, blogId, mTimeframe);
            restClientUtils.get(singlePostRestPath, vListener, vListener);
            AppLog.d(AppLog.T.STATS, "Enqueuing the following Stats request " + singlePostRestPath);
        }
    }

    @Override
    public void onMoreDataRequested(StatsService.StatsEndpointsEnum endPointNeedUpdate, int pageNumber) {
        if (mFragment == null) {
            return;
        }

        if (!mFragment.isAdded()) {
            return;
        }

        if (mIsUpdatingStats) {
            AppLog.d(AppLog.T.STATS, "Already loading data");
            return;
        }

        if (!NetworkUtils.isNetworkAvailable(this)) {
            mSwipeToRefreshHelper.setRefreshing(false);
            AppLog.w(AppLog.T.STATS, "ViewAll on "+ mFragment.getTag() + " > no connection, update canceled");
            return;
        }

        mIsUpdatingStats = true;
        mSwipeToRefreshHelper.setRefreshing(true);
        final RestClientUtils restClientUtils = WordPress.getRestClientUtilsV1_1();
        final String blogId = StatsUtils.getBlogId(mLocalBlogID);

        String singlePostRestPath = getRestPath(endPointNeedUpdate, pageNumber);
        RestListener vListener = new RestListener(this, endPointNeedUpdate, blogId, mTimeframe);
        restClientUtils.get(singlePostRestPath, vListener, vListener);
        AppLog.d(AppLog.T.STATS, "Enqueuing the following Stats request " + singlePostRestPath);
    }

    @Override
    public void onRefreshRequested(StatsService.StatsEndpointsEnum[] endPointsNeedUpdate) {
        mHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                refreshStats();
            }
        }, 75L);
    }

    private class RestListener implements RestRequest.Listener, RestRequest.ErrorListener {
        private final String mRequestBlogId;
        private final StatsTimeframe mTimeframe;
        private final StatsService.StatsEndpointsEnum mEndpointName;
        private final WeakReference<Activity> mActivityRef;

        public RestListener(Activity activity, StatsService.StatsEndpointsEnum endpointName, String blogId, StatsTimeframe timeframe) {
                mActivityRef = new WeakReference<>(activity);
                mRequestBlogId = blogId;
                mTimeframe = timeframe;
                mEndpointName = endpointName;
        }

        @Override
        public void onResponse(final JSONObject response) {
            if (mActivityRef.get() == null || mActivityRef.get().isFinishing()) {
                return;
            }
            if (!mFragment.isAdded()) {
                return;
            }
            mIsUpdatingStats = false;
            mSwipeToRefreshHelper.setRefreshing(false);
            // single background thread used to parse the response in BG.
            ThreadPoolExecutor parseResponseExecutor = (ThreadPoolExecutor) Executors.newFixedThreadPool(1);
            parseResponseExecutor.submit(new Thread() {
                @Override
                public void run() {
                    if (response != null) {
                        try {
                            //AppLog.d(AppLog.T.STATS, response.toString());
                            final Serializable resp = StatsUtils.parseResponse(mEndpointName, mRequestBlogId, response);
                            EventBus.getDefault().post(new StatsEvents.SectionUpdated(mEndpointName, resp));
                        } catch (JSONException e) {
                            AppLog.e(AppLog.T.STATS, e);
                        }
                    }
                }
            });
        }

        @Override
        public void onErrorResponse(final VolleyError volleyError) {
            AppLog.e(AppLog.T.STATS, "Error while reading Stats details!");
            StatsUtils.logVolleyErrorDetails(volleyError);

            if (mActivityRef.get() == null || mActivityRef.get().isFinishing()) {
                return;
            }
            if (!mFragment.isAdded()) {
                return;
            }

            resetModelVariables();
            mFragment.showHideNoResultsUI(true);

            ToastUtils.showToast(mActivityRef.get(),
                    mActivityRef.get().getString(R.string.error_refresh_stats),
                    ToastUtils.Duration.LONG);
            mIsUpdatingStats = false;
            mSwipeToRefreshHelper.setRefreshing(false);
        }
    }

    private void resetModelVariables() {
        mRestResponse = null;
    }

    // Fragments call these two methods below to access the current timeframe/date selected by the user.
    @Override
    public String getCurrentDate() {
        return mDate;
    }

    @Override
    public StatsTimeframe getCurrentTimeFrame() {
        return mTimeframe;
    }
}
