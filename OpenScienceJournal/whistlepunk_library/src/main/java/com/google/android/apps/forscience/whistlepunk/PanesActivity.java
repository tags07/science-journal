/*
 *  Copyright 2017 Google Inc. All Rights Reserved.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package com.google.android.apps.forscience.whistlepunk;

import android.app.Activity;
import android.app.Fragment;
import android.app.FragmentManager;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.annotation.VisibleForTesting;
import android.support.design.widget.CoordinatorLayout;
import android.support.design.widget.TabLayout;
import android.support.v13.app.FragmentPagerAdapter;
import android.support.v4.view.ViewPager;
import android.support.v7.app.AppCompatActivity;
import android.text.TextUtils;
import android.util.Log;
import android.util.SparseArray;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ProgressBar;

import com.google.android.apps.forscience.whistlepunk.analytics.TrackerConstants;
import com.google.android.apps.forscience.whistlepunk.filemetadata.Experiment;
import com.google.android.apps.forscience.whistlepunk.filemetadata.Label;
import com.google.android.apps.forscience.whistlepunk.project.experiment.ExperimentDetailsFragment;
import com.jakewharton.rxbinding2.view.RxView;
import com.tbruyelle.rxpermissions2.RxPermissions;

import io.reactivex.Observable;
import io.reactivex.Single;
import io.reactivex.subjects.BehaviorSubject;
import io.reactivex.subjects.SingleSubject;

public class PanesActivity extends AppCompatActivity implements RecordFragment.CallbacksProvider,
        CameraFragment.ListenerProvider, TextToolFragment.ListenerProvider {
    /**
     * Just for development, will be removed once shared control bar works
     */
    private static final boolean SHARED_CONTROL_BAR = true;

    private static final String TAG = "PanesActivity";
    private static final String EXTRA_EXPERIMENT_ID = "experimentId";
    private static final String KEY_SELECTED_TAB_INDEX = "selectedTabIndex";
    private final SnackbarManager mSnackbarManager;

    private ProgressBar mRecordingBar;
    private int mSelectedTabIndex;
    private PanesBottomSheetBehavior mBottomBehavior;
    private boolean mTabsInitialized;
    private BehaviorSubject<Integer> mActivityHeight = BehaviorSubject.create();
    private BehaviorSubject<Integer> mBottomSheetState = BehaviorSubject.create();
    private ImageButton mGrabber;

    public PanesActivity() {
        mSnackbarManager = new SnackbarManager();
    }

    private static enum ToolTab {
        NOTES(R.string.tab_description_add_note, R.drawable.ic_comment_white_24dp, "NOTES") {
            @Override
            public Fragment createFragment(String experimentId, Activity activity) {
                return TextToolFragment.newInstance();
            }

            @Override
            public void connectControls(Fragment fragment, FrameLayout controlBar,
                    ControlBarController controlBarController,
                    Observable<Integer> availableHeight) {
                TextToolFragment ttf = (TextToolFragment) fragment;
                LayoutInflater.from(controlBar.getContext())
                              .inflate(R.layout.text_action_bar, controlBar, true);
                ttf.attachButtons(controlBar);
                ttf.listenToAvailableHeight(availableHeight);
            }

            @Override
            public Runnable onGainedFocus(Object object, Activity activity) {
                TextToolFragment ttf = (TextToolFragment) object;
                return () -> {
                    // when losing focus, close keyboard
                    closeKeyboard(activity);
                    ttf.onLosingFocus();
                };
            }
        }, OBSERVE(R.string.tab_description_observe, R.drawable.sensortab_white_24dp, "OBSERVE") {
            @Override
            public Fragment createFragment(String experimentId, Activity activity) {
                return RecordFragment.newInstance(experimentId, false);
            }

            @Override
            public void connectControls(Fragment fragment, FrameLayout controlBar,
                    ControlBarController controlBarController,
                    Observable<Integer> availableHeight) {
                LayoutInflater.from(controlBar.getContext())
                              .inflate(R.layout.observe_action_bar, controlBar, true);
                controlBarController.attachRecordButtons(controlBar);
                controlBarController.attachElapsedTime(controlBar, (RecordFragment) fragment);
            }
        }, CAMERA(R.string.tab_description_camera, R.drawable.ic_camera_white_24dp, "CAMERA") {
            @Override
            public Fragment createFragment(String experimentId, Activity activity) {
                // TODO: b/62022245
                return CameraFragment.newInstance(new RxPermissions(activity));
            }

            @Override
            public Runnable onGainedFocus(Object object, Activity activity) {
                CameraFragment fragment = (CameraFragment) object;
                fragment.onGainedFocus();
                return () -> fragment.onLosingFocus();
            }

            @Override
            public void connectControls(Fragment fragment, FrameLayout controlBar,
                    ControlBarController controlBarController,
                    Observable<Integer> availableHeight) {
                CameraFragment cf = (CameraFragment) fragment;
                LayoutInflater.from(controlBar.getContext())
                              .inflate(R.layout.camera_action_bar, controlBar, true);
                cf.attachButtons(controlBar);
            }
        }, GALLERY(R.string.tab_description_gallery, R.drawable.ic_photo_white_24dp, "GALLERY") {
            @Override
            public Fragment createFragment(String experimentId, Activity activity) {
                return GalleryFragment.newInstance(new RxPermissions(activity),
                        !SHARED_CONTROL_BAR);
            }

            @Override
            public void connectControls(Fragment fragment, FrameLayout controlBar,
                    ControlBarController controlBarController,
                    Observable<Integer> availableHeight) {
                // TODO: is this duplicated code?
                GalleryFragment gf = (GalleryFragment) fragment;
                LayoutInflater.from(controlBar.getContext())
                              .inflate(R.layout.gallery_action_bar, controlBar, true);
                gf.attachAddButton(controlBar);
            }
        };
        private final int mContentDescriptionId;
        private final int mIconId;
        private final String mLoggingName;

        ToolTab(int contentDescriptionId, int iconId, String loggingName) {
            mContentDescriptionId = contentDescriptionId;
            mIconId = iconId;
            mLoggingName = loggingName;
        }

        public abstract Fragment createFragment(String experimentId, Activity activity);

        public int getContentDescriptionId() {
            return mContentDescriptionId;
        }

        public int getIconId() {
            return mIconId;
        }

        public String getLoggingName() {
            return mLoggingName;
        }

        /**
         * Called when focus is gained by this tab.
         *
         * @return a Runnable that should be called when focus is lost, or null to do nothing.
         */
        public Runnable onGainedFocus(Object object, Activity activity) {
            // by default, do nothing
            return null;
        }

        public abstract void connectControls(Fragment fragment, FrameLayout controlBar,
                ControlBarController controlBarController,
                Observable<Integer> availableHeight);
    }

    private static void closeKeyboard(Activity activity) {
        View view = activity.getCurrentFocus();
        if (view != null) {
            InputMethodManager imm = (InputMethodManager) activity.getSystemService(
                    Context.INPUT_METHOD_SERVICE);
            imm.hideSoftInputFromWindow(view.getWindowToken(), 0);
        }
    }

    public static void launch(Context context, String experimentId) {
        Intent intent = launchIntent(context, experimentId);
        context.startActivity(intent);
    }

    @NonNull
    public static Intent launchIntent(Context context, String experimentId) {
        Intent intent = new Intent(context, PanesActivity.class);
        intent.putExtra(EXTRA_EXPERIMENT_ID, experimentId);
        return intent;
    }

    private ExperimentDetailsFragment mExperimentFragment = null;

    /**
     * SingleSubject remembers the loaded value (if any) and delivers it to any observers.
     * <p>
     * TODO: use mActiveExperiment for other places that need an experiment in this class and
     * fragments.
     */
    private SingleSubject<Experiment> mActiveExperiment = SingleSubject.create();
    private RxEvent mDestroyed = new RxEvent();

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.panes_layout);
        RxView.layoutChangeEvents(findViewById(R.id.container)).subscribe(event -> {
            int bottom = event.bottom();
            int top = event.top();
            int height = bottom - top;
            mActivityHeight.onNext(height);
        });

        mRecordingBar = (ProgressBar) findViewById(R.id.recording_progress_bar);
        mGrabber = (ImageButton) findViewById(R.id.grabber);

        String experimentId = getIntent().getStringExtra(EXTRA_EXPERIMENT_ID);

        mSelectedTabIndex = 0;
        if (savedInstanceState != null) {
            mSelectedTabIndex = savedInstanceState.getInt(KEY_SELECTED_TAB_INDEX);
        }

        // By adding the subscription to mUntilDestroyed, we make sure that we can disconnect from
        // the experiment stream when this activity is destroyed.
        mActiveExperiment.subscribe(experiment -> {
            setupViews(experiment);
            setExperimentFragmentId(experiment);
            AppSingleton.getInstance(this).getRecorderController().watchRecordingStatus()
                        .firstElement().subscribe(status -> {
                if (status.state == RecordingState.ACTIVE) {
                    showRecordingBar();
                    Log.d(TAG, "start recording");
                    mExperimentFragment.onStartRecording(
                            status.currentRecording.getRunId());
                } else {
                    hideRecordingBar();
                }
            });
        });

        Single<Experiment> exp = whenSelectedExperiment(experimentId, getDataController());
        exp.takeUntil(mDestroyed.happensNext()).subscribe(mActiveExperiment);

        AppSingleton.getInstance(this)
                    .whenLabelsAdded()
                    .takeUntil(mDestroyed.happens())
                    .subscribe(event -> onLabelAdded(event.getTrialId()));

        View bottomControlBar = findViewById(R.id.bottom_control_bar);
        setCoordinatorBehavior(bottomControlBar, new BottomDependentBehavior() {
            @Override
            public boolean onDependentViewChanged(CoordinatorLayout parent, View child,
                    View dependency) {
                int dependencyTop = dependency.getTop();
                int belowHalf = dependencyTop - (parent.getHeight() / 2);

                // Translate down once the drawer is below halfway
                int translateY = Math.max(0, belowHalf);
                if (child.getTranslationY() != translateY) {
                    child.setTranslationY(translateY);
                    return true;
                }

                return false;
            }
        });
        WhistlePunkApplication.getUsageTracker(this).trackScreenView(
                TrackerConstants.SCREEN_PANES);
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        outState.putInt(KEY_SELECTED_TAB_INDEX, mSelectedTabIndex);
        super.onSaveInstanceState(outState);
    }

    @VisibleForTesting
    public static Single<Experiment> whenSelectedExperiment(String experimentId,
            DataController dataController) {
        if (Log.isLoggable(TAG, Log.INFO)) {
            Log.i(TAG, "Launching specified experiment id: " + experimentId);
        }
        return RxDataController.getExperimentById(dataController, experimentId);
    }

    public void onArchivedStateChanged(Experiment experiment) {
        setupViews(experiment);
        findViewById(R.id.container).requestLayout();
    }

    private void setupViews(Experiment experiment) {
        ControlBarController controlBarController =
                new ControlBarController(getFragmentManager(), experiment.getExperimentId(),
                        mSnackbarManager);

        ViewPager pager = (ViewPager) findViewById(R.id.pager);
        View bottomSheet = findViewById(R.id.bottom);
        TabLayout toolPicker = (TabLayout) findViewById(R.id.tool_picker);
        View experimentPane = findViewById(R.id.experiment_pane);
        View controlBarSpacer = findViewById(R.id.control_bar_spacer);
        FrameLayout controlBar = (FrameLayout) findViewById(R.id.bottom_control_bar);

        if (!experiment.isArchived()) {
            setCoordinatorBehavior(experimentPane, new BottomDependentBehavior() {
                @Override
                public boolean onDependentViewChanged(CoordinatorLayout parent, View child,
                        View dependency) {
                    int desiredBottom = dependency.getTop();
                    int currentBottom = child.getBottom();

                    if (desiredBottom != currentBottom && dependency.getVisibility() != View.GONE
                        && dependency.getId() == R.id.bottom) {
                        ViewGroup.LayoutParams layoutParams = child.getLayoutParams();
                        layoutParams.height = desiredBottom - child.getTop();
                        child.setLayoutParams(layoutParams);
                        return true;
                    } else {
                        return super.onDependentViewChanged(parent, child, dependency);
                    }
                }
            });

            controlBarSpacer.setVisibility(View.VISIBLE);
            controlBar.setVisibility(View.VISIBLE);
            bottomSheet.setVisibility(View.VISIBLE);
            findViewById(R.id.shadow).setVisibility(View.VISIBLE);
            mBottomBehavior = (PanesBottomSheetBehavior)
                    ((CoordinatorLayout.LayoutParams) bottomSheet.getLayoutParams())
                            .getBehavior();
            mBottomBehavior.setBottomSheetCallback(
                    new PanesBottomSheetBehavior.BottomSheetCallback() {
                        @Override
                        public void onStateChanged(@NonNull View bottomSheet, int newState) {
                            mBottomSheetState.onNext(newState);
                            if (mBottomBehavior.getState() ==
                                    PanesBottomSheetBehavior.STATE_COLLAPSED) {
                                mGrabber.setContentDescription(getResources().getString(
                                        R.string.btn_show_tools));
                            } else if (mBottomBehavior.getState() ==
                                    PanesBottomSheetBehavior.STATE_MIDDLE) {
                                mGrabber.setContentDescription(getResources().getString(
                                        R.string.btn_expand_tools));
                            } else if (mBottomBehavior.getState() ==
                                    PanesBottomSheetBehavior.STATE_EXPANDED) {
                                mGrabber.setContentDescription(getResources().getString(
                                        R.string.btn_hide_tools));
                            }
                        }

                        @Override
                        public void onSlide(@NonNull View bottomSheet, float slideOffset) {
                        }
                    });

            // TODO: could this be FragmentStatePagerAdapter?  Would the fragment lifecycle methods
            //       get called in time to remove the camera preview views and avoid b/64442501?
            final FragmentPagerAdapter adapter = new FragmentPagerAdapter(getFragmentManager()) {
                // TODO: extract and test this.
                private int mPreviousPrimary = -1;
                private Runnable mOnLosingFocus = null;

                @Override
                public Fragment getItem(int position) {
                    if (position >= ToolTab.values().length) {
                        return null;
                    }
                    return getToolTab(position).createFragment(experiment.getExperimentId(),
                            PanesActivity.this);
                }

                private ToolTab getToolTab(int position) {
                    return ToolTab.values()[position];
                }

                @Override
                public int getCount() {
                    return ToolTab.values().length;
                }

                @Override
                public void setPrimaryItem(ViewGroup container, int position, Object object) {
                    if (position != mPreviousPrimary && mOnLosingFocus != null) {
                        mOnLosingFocus.run();
                        mOnLosingFocus = null;
                    }
                    super.setPrimaryItem(container, position, object);
                    if (position != mPreviousPrimary) {
                        ToolTab toolTab = getToolTab(position);
                        FrameLayout controlBar =
                                (FrameLayout) findViewById(R.id.bottom_control_bar);
                        controlBar.removeAllViews();
                        toolTab.connectControls((Fragment) object, controlBar,
                                controlBarController, availableTabHeight());
                        mOnLosingFocus = toolTab.onGainedFocus(object, PanesActivity.this);
                        mPreviousPrimary = position;
                    }
                }

            };
            pager.setAdapter(adapter);

            initializeToolPicker(toolPicker, pager, experiment, bottomSheet, experimentPane);
        } else {
            controlBar.setVisibility(View.GONE);
            controlBarSpacer.setVisibility(View.GONE);
            bottomSheet.setVisibility(View.GONE);
            findViewById(R.id.shadow).setVisibility(View.GONE);
            experimentPane.setLayoutParams(new CoordinatorLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

            // Clear the tabs, which releases all cameras and removes all triggers etc.
            pager.setAdapter(null);
        }
    }

    private void initializeToolPicker(TabLayout toolPicker, ViewPager pager,
            Experiment experiment, View bottomSheet, View experimentPane) {
        if (toolPicker.getTabCount() > 0) {
            ViewGroup.LayoutParams layoutParams = experimentPane.getLayoutParams();
            layoutParams.height = bottomSheet.getTop();
            experimentPane.setLayoutParams(layoutParams);
            toolPicker.getTabAt(mSelectedTabIndex).select();

            // It's already initialized. Don't do it again!
            return;
        }

        mBottomBehavior.setPeekHeight(getResources().getDimensionPixelSize(
                R.dimen.panes_toolbar_height));

        for (ToolTab tab : ToolTab.values()) {
            TabLayout.Tab layoutTab = toolPicker.newTab();
            layoutTab.setContentDescription(tab.getContentDescriptionId());
            layoutTab.setIcon(tab.getIconId());
            layoutTab.setTag(tab);
            toolPicker.addTab(layoutTab);
        }

        toolPicker.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
            @Override
            public void onTabSelected(TabLayout.Tab tab) {
                ToolTab toolTab = (ToolTab) tab.getTag();
                mSelectedTabIndex = toolTab.ordinal();
                pager.setCurrentItem(mSelectedTabIndex, true);
                openPaneIfNeeded();
            }

            @Override
            public void onTabUnselected(TabLayout.Tab tab) {

            }

            @Override
            public void onTabReselected(TabLayout.Tab tab) {
                if (pager.getCurrentItem() != mSelectedTabIndex) {
                    // After archive/unarchive we can get a state where the tab is technically
                    // selected but the pager has not updated properly. This forces the
                    // update to the pager fragment.
                    onTabSelected(tab);
                } else {
                    // Pull it up if it's the already selected item.
                    openPaneIfNeeded();
                }
            }
        });
        mTabsInitialized = false;
        toolPicker.getTabAt(mSelectedTabIndex).select();
        if (experiment.getLabelCount() > 0 || experiment.getTrialCount() > 0) {
            mBottomBehavior.setState(PanesBottomSheetBehavior.STATE_COLLAPSED);
            mGrabber.setContentDescription(getResources().getString(
                    R.string.btn_show_tools));
        } else {
            mBottomBehavior.setState(PanesBottomSheetBehavior.STATE_MIDDLE);
            mGrabber.setContentDescription(getResources().getString(
                    R.string.btn_expand_tools));
        }
        mTabsInitialized = true;
    }

    private void setupGrabber() {
        if (AccessibilityUtils.isAccessibilityManagerEnabled(this)) {
            mGrabber.setOnClickListener(view -> {
                if (mBottomBehavior.getState() ==
                        PanesBottomSheetBehavior.STATE_COLLAPSED) {
                    changeSheetState(PanesBottomSheetBehavior.STATE_COLLAPSED,
                            PanesBottomSheetBehavior.STATE_MIDDLE);
                } else if (mBottomBehavior.getState() ==
                        PanesBottomSheetBehavior.STATE_MIDDLE) {
                    changeSheetState(PanesBottomSheetBehavior.STATE_MIDDLE,
                            PanesBottomSheetBehavior.STATE_EXPANDED);
                } else if (mBottomBehavior.getState() ==
                        PanesBottomSheetBehavior.STATE_EXPANDED) {
                    changeSheetState(PanesBottomSheetBehavior.STATE_EXPANDED,
                            PanesBottomSheetBehavior.STATE_COLLAPSED);
                }
            });
        }
    }

    private Observable<Integer> availableTabHeight() {
        return Observable.combineLatest(mActivityHeight, mBottomSheetState,
                (activityHeight, sheetState) -> {
                    if (mActiveExperiment.getValue().isArchived()) {
                        // No matter the state, the control bar is hidden when archived.
                        return 0;
                    }
                    switch (sheetState) {
                        case PanesBottomSheetBehavior.STATE_COLLAPSED:
                            return 0;
                        case PanesBottomSheetBehavior.STATE_EXPANDED:
                            return activityHeight;
                        case PanesBottomSheetBehavior.STATE_MIDDLE:
                            return activityHeight / 2;
                    }
                    return activityHeight;
                });
    }

    private void setCoordinatorBehavior(View view, BottomDependentBehavior behavior) {
        CoordinatorLayout.LayoutParams params =
                (CoordinatorLayout.LayoutParams) view.getLayoutParams();
        params.setBehavior(behavior);
        view.setLayoutParams(params);
    }

    private void setExperimentFragmentId(Experiment experiment) {
        if (mExperimentFragment == null) {
            boolean createTaskStack = false;
            Label deletedLabel = getDeletedLabel();
            mExperimentFragment =
                    ExperimentDetailsFragment.newInstance(experiment.getExperimentId(),
                            createTaskStack, deletedLabel);

            FragmentManager fragmentManager = getFragmentManager();
            fragmentManager.beginTransaction()
                           .replace(R.id.experiment_pane, mExperimentFragment)
                           .commit();
        } else {
            mExperimentFragment.setExperimentId(experiment.getExperimentId());
        }
    }

    private Label getDeletedLabel() {
        Bundle extras = getIntent().getExtras();
        if (extras == null) {
            return null;
        } else {
            return extras.getParcelable(ExperimentDetailsFragment.ARG_DELETED_LABEL);
        }
    }

    @Override
    protected void onDestroy() {
        mDestroyed.onHappened();
        super.onDestroy();
    }

    @Override
    public void onResume() {
        super.onResume();
        if (!isMultiWindowEnabled()) {
            updateRecorderControllerForResume();
        }
        setupGrabber();
    }

    @Override
    protected void onPause() {
        if (!isMultiWindowEnabled()) {
            updateRecorderControllerForPause();
            logPanesState(TrackerConstants.ACTION_PAUSED);
        }
        super.onPause();
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (isMultiWindowEnabled()) {
            updateRecorderControllerForResume();
        }
    }

    @Override
    protected void onStop() {
        if (isMultiWindowEnabled()) {
            updateRecorderControllerForPause();
            logPanesState(TrackerConstants.ACTION_PAUSED);
        }
        super.onStop();
    }

    private void logPanesState(String action) {
        SparseArray<String> dimensions = new SparseArray<>();
        if (mTabsInitialized) {
            dimensions.append(TrackerConstants.PANES_DRAWER_STATE,
                    mBottomBehavior.getDrawerStateForLogging());
            dimensions.append(TrackerConstants.PANES_TOOL_NAME,
                    ToolTab.values()[mSelectedTabIndex].getLoggingName());
        }
        WhistlePunkApplication.getUsageTracker(this).trackDimensionEvent(
                TrackerConstants.CATEGORY_PANES, action, dimensions);
    }

    private boolean isMultiWindowEnabled() {
        return MultiWindowUtils.isMultiWindowEnabled(getApplicationContext());
    }

    private void updateRecorderControllerForResume() {
        RecorderController rc = AppSingleton.getInstance(this).getRecorderController();
        rc.setRecordActivityInForeground(true);
    }

    private void updateRecorderControllerForPause() {
        RecorderController rc = AppSingleton.getInstance(this).getRecorderController();
        rc.setRecordActivityInForeground(false);
    }

    @Override
    public void onBackPressed() {
        if (mExperimentFragment.handleOnBackPressed()) {
            return;
        }
        super.onBackPressed();
    }

    @Override
    public RecordFragment.UICallbacks getRecordFragmentCallbacks() {
        return new RecordFragment.UICallbacks() {
            @Override
            void onRecordingSaved(String runId, Experiment experiment) {
                logPanesState(TrackerConstants.ACTION_RECORDED);
                mExperimentFragment.loadExperimentData(experiment);
            }

            @Override
            public void onRecordingRequested(String experimentName, boolean userInitiated) {
                showRecordingBar();
                if (mTabsInitialized && userInitiated) {
                    expandSheet();
                }
            }

            @Override
            void onRecordingStart(RecordingStatus recordingStatus) {
                if (recordingStatus.state == RecordingState.STOPPING) {
                    // If we call "recording start" when stopping it leads to extra work.
                    return;
                }
                String trialId = recordingStatus.getCurrentRunId();
                if (!TextUtils.isEmpty(trialId)) {
                    mExperimentFragment.onStartRecording(trialId);
                }
            }

            @Override
            void onRecordingStopped() {
                hideRecordingBar();
                mExperimentFragment.onStopRecording();
                dropToHalfScreenIfNeeded();
            }

            @Override
            void maximizeFragment() {
                expandSheet();
            }
        };
    }

    @Override
    public CameraFragment.CameraFragmentListener getCameraFragmentListener() {
        return new CameraFragment.CameraFragmentListener() {
            @Override
            public void onPictureLabelTaken(final Label label) {
                addNewLabel(label);
            }

            @Override
            public Observable<String> getActiveExperimentId() {
                return PanesActivity.this.getActiveExperimentId();
            }
        };
    }

    @Override
    public TextToolFragment.TextLabelFragmentListener getTextLabelFragmentListener() {
        return result -> addNewLabel(result);
    }

    private void addNewLabel(Label label) {
        // Get the most recent experiment, or wait if none has been loaded yet.
        mActiveExperiment.subscribe(e -> {
            // if it is recording, add it to the recorded trial instead!
            String trialId = mExperimentFragment.getActiveRecordingId();
            if (TextUtils.isEmpty(trialId)) {
                e.addLabel(label);
            } else {
                e.getTrial(trialId).addLabel(label);
            }
            RxDataController.updateExperiment(getDataController(), e)
                            .subscribe(() -> onLabelAdded(trialId));
        });
    }

    private void dropToHalfScreenIfNeeded() {
        changeSheetState(PanesBottomSheetBehavior.STATE_EXPANDED,
                PanesBottomSheetBehavior.STATE_MIDDLE);
    }

    private void changeSheetState(int fromState, int toState) {
        if (mBottomBehavior == null) {
            // TODO: wire up bottom behavior at creation
            // Experiment is archived, there's no sheet to change
            return;
        }
        if (mBottomBehavior.getState() == fromState) {
            mBottomBehavior.setState(toState);
        }
    }

    private void expandSheet() {
        if (mBottomBehavior.getState() != PanesBottomSheetBehavior.STATE_EXPANDED) {
            mBottomBehavior.setState(PanesBottomSheetBehavior.STATE_EXPANDED);
        }
    }

    private void onLabelAdded(String trialId) {
        logPanesState(TrackerConstants.ACTION_LABEL_ADDED);
        if (TextUtils.isEmpty(trialId)) {
            // TODO: is this expensive?  Should we trigger a more incremental update?
            mExperimentFragment.reloadAndScrollToBottom();
        } else {
            mExperimentFragment.onRecordingTrialUpdated(trialId);
        }
        dropToHalfScreenIfNeeded();
    }

    private DataController getDataController() {
        return AppSingleton.getInstance(PanesActivity.this).getDataController();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String permissions[],
            int[] grantResults) {
        PermissionUtils.onRequestPermissionsResult(this, requestCode, permissions,
                grantResults);
    }

    private void showRecordingBar() {
        if (mRecordingBar != null) {
            mRecordingBar.setVisibility(View.VISIBLE);
            mBottomBehavior.setPeekHeight(
                    mRecordingBar.getResources().getDimensionPixelSize(
                            R.dimen.panes_toolbar_height) +
                    mRecordingBar.getResources().getDimensionPixelSize(
                            R.dimen.recording_indicator_height));
        }
    }

    private void hideRecordingBar() {
        if (mRecordingBar != null) {
            mRecordingBar.setVisibility(View.GONE);
            if (mBottomBehavior != null) {
                // Null if we are in an archived experiment.
                mBottomBehavior.setPeekHeight(mRecordingBar.getResources()
                                                           .getDimensionPixelSize(
                                                                   R.dimen.panes_toolbar_height));
            }
        }
    }

    private void openPaneIfNeeded() {
        // Only do the work if it is initialized. This keeps the pane from jumping open and closed
        // when the views are first loaded.
        if (mTabsInitialized) {
            // Clicking a tab raises the pane to middle if it was at the bottom.
            changeSheetState(PanesBottomSheetBehavior.STATE_COLLAPSED,
                    PanesBottomSheetBehavior.STATE_MIDDLE);
        }
    }

    private Observable<String> getActiveExperimentId() {
        return mActiveExperiment.map(e -> e.getExperimentId()).toObservable();
    }

    private static class BottomDependentBehavior extends CoordinatorLayout.Behavior {
        @Override
        public boolean layoutDependsOn(CoordinatorLayout parent, View child,
                View dependency) {
            if (dependency.getId() == R.id.bottom &&
                dependency.getVisibility() != View.GONE) {
                return true;
            } else {
                return super.layoutDependsOn(parent, child, dependency);
            }
        }
    }
}
