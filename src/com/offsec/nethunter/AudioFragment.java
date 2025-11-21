package com.offsec.nethunter;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.text.SpannableString;
import android.text.style.ForegroundColorSpan;
import android.Manifest;
import android.content.pm.PackageManager;

import com.offsec.nethunter.utils.PermissionCheck;

import androidx.core.content.ContextCompat;
import androidx.core.content.res.ResourcesCompat;
import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;

import com.offsec.nethunter.audio.AudioPlayState;
import com.offsec.nethunter.audio.AudioPlaybackService;

import java.util.ArrayList;
import java.util.Locale;
import java.util.Arrays;
import java.util.List;

public class AudioFragment extends Fragment {
    public final static String TAG = "AudioFragment";
    public static final int DEFAULT_INDEX_BUFFER_HEADROOM = 4;
    public static final int DEFAULT_INDEX_TARGET_LATENCY = 6;
    private static final List<Long> VALUES_BUFFER_HEADROOM = Arrays.asList(0L, 15625L, 31250L, 62500L, 125000L, 250000L, 500000L, 1000000L, 2000000L);
    private static final List<Long> VALUES_TARGET_LATENCY = Arrays.asList(0L, 15625L, 31250L, 62500L, 125000L, 250000L, 500000L, 1000000L, 2000000L, 5000000L, 10000000L, -1L);
    private ActivityResultLauncher<String> audioPermissionLauncher;
    private ActivityResultLauncher<String> notificationPermissionLauncher;
    private Button playButton;
    private Spinner bufferHeadroomSpinner;
    private Spinner targetLatencySpinner;
    private EditText serverInput;
    private EditText portInput;
    private CheckBox autoStartCheckBox;
    private TextView errorText;
    private ScrollView fullScrollView;
    private Throwable error;
    private boolean isServiceBound = false;
    private AudioPlaybackService boundService;

    private final ServiceConnection mConnection = new ServiceConnection() {
        public void onServiceConnected(ComponentName className, IBinder service) {
            boundService = ((AudioPlaybackService.LocalBinder) service).getService();
            if (boundService != null) {
                boundService.playState().observe(getViewLifecycleOwner(), AudioFragment.this::updatePlayStateInternal);
                boundService.showNotification();
                updatePrefs(boundService);

                if (boundService.getAutostartPref() && boundService.isStartable()) {
                    play();
                }
            }
            playButton.setEnabled(true); // Enable play button when service is bound
            isServiceBound = true;
        }

        public void onServiceDisconnected(ComponentName className) {
            boundService = null;
            isServiceBound = false;
        }
    };

    public AudioFragment() {
        this.error = new Throwable("No error provided");
    }

    public Throwable getError() {
        return error;
    }

    // Add the newInstance method
    public static AudioFragment newInstance(int itemId) {
        AudioFragment fragment = new AudioFragment();
        Bundle args = new Bundle();
        args.putInt("ITEM_ID", itemId);
        fragment.setArguments(args);
        return fragment;
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        audioPermissionLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestPermission(),
                isGranted -> {
                    String permission = Manifest.permission.RECORD_AUDIO;
                    if (isGranted) {
                        Log.d(TAG, "Permission granted: " + permission);
                        play();
                    } else {
                        Log.d(TAG, "Permission denied: " + permission);
                        if (errorText != null) {
                            errorText.setText(R.string.audio_permission_denied_cannot_start_playback);
                        }
                    }
                }
        );
        notificationPermissionLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestPermission(),
                isGranted -> {
                    String permission = Manifest.permission.POST_NOTIFICATIONS;
                    if (isGranted) {
                        Log.d(TAG, "Permission granted: " + permission);
                        // no immediate action; play() will continue when user taps again
                    } else {
                        Log.d(TAG, "Permission denied: " + permission);
                        // We still try to play, but Android 13+ may block FGS without notifications allowed
                        if (errorText != null) {
                            errorText.setText(R.string.notification_permission_denied);
                        }
                    }
                }
        );
    }

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        //Log.d(TAG, "onCreateAudioFragment");

        // Retrieve the itemId passed in newInstance
        if (getArguments() != null) {
            int itemId = getArguments().getInt("ITEM_ID", -1);
        }

        // Log or use the itemId as needed
        //Log.d(TAG, "Received itemId: " + itemId);

        // Inflate the layout for this fragment
        View view = inflater.inflate(R.layout.audio, container, false);

        // Initialize UI elements
        TextView audioSettingsHeader = view.findViewById(R.id.audioSettingsHeader);
        LinearLayout audioSettingsContent = view.findViewById(R.id.audioSettingsContent);

        audioSettingsHeader.setOnClickListener(v -> {
            if (audioSettingsContent.getVisibility() == View.VISIBLE) {
                audioSettingsContent.setVisibility(View.GONE);
                audioSettingsHeader.setCompoundDrawablesRelativeWithIntrinsicBounds(
                        null, null, ResourcesCompat.getDrawable(getResources(), R.drawable.ic_expand_more, null), null);
            } else {
                audioSettingsContent.setVisibility(View.VISIBLE);
                audioSettingsHeader.setCompoundDrawablesRelativeWithIntrinsicBounds(
                        null, null, ResourcesCompat.getDrawable(getResources(), R.drawable.ic_expand_less, null), null);
            }
        });

        playButton = view.findViewById(R.id.ButtonPlay);
        playButton.setEnabled(false);
        playButton.setOnClickListener(v -> {
            if (checkAndRequestNotificationPermission()) return;
            if (checkAndRequestAudioPermission()) return;
            if (boundService != null) {
                if (boundService.isPlaying()) {
                    stop();
                } else {
                    play();
                }
            } else {
                // Optionally, show a message or handle the case where the service is not connected yet
                errorText.setText(R.string.audio_service_not_connected);
            }
        });

        serverInput = view.findViewById(R.id.EditTextServer);
        portInput = view.findViewById(R.id.EditTextPort);
        autoStartCheckBox = view.findViewById(R.id.auto_start);
        errorText = view.findViewById(R.id.errorText);
        bufferHeadroomSpinner = view.findViewById(R.id.bufferHeadroomSpinner);

        List<String> formattedBufferHeadroom = formatValuesAsSeconds(VALUES_BUFFER_HEADROOM);
        ArrayAdapter<String> bufferAdapter = new ArrayAdapter<>(requireActivity(), android.R.layout.simple_spinner_dropdown_item, formattedBufferHeadroom);
        bufferHeadroomSpinner.setAdapter(bufferAdapter);
        targetLatencySpinner = view.findViewById(R.id.targetLatencySpinner);

        List<String> formattedTargetLatency = formatValuesAsSeconds(VALUES_TARGET_LATENCY);
        ArrayAdapter<String> latencyAdapter = new ArrayAdapter<>(requireActivity(), android.R.layout.simple_spinner_dropdown_item, formattedTargetLatency);
        targetLatencySpinner.setAdapter(latencyAdapter);
        fullScrollView = view.findViewById(R.id.fullScrollView);

        Button clearLogsButton = view.findViewById(R.id.clearLogsButton);
        clearLogsButton.setOnClickListener(v -> errorText.setText(""));

        return view;
    }

    private boolean checkAndRequestAudioPermission() {
        String permission = Manifest.permission.RECORD_AUDIO;
        if (ContextCompat.checkSelfPermission(requireContext(), permission)
                != PackageManager.PERMISSION_GRANTED) {
            Log.d(TAG, "Permission check: " + permission + " NOT GRANTED, requesting...");
            audioPermissionLauncher.launch(permission);
            return true;
        }
        Log.d(TAG, "Permission check: " + permission + " GRANTED");
        return false;
    }

    private boolean checkAndRequestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            PermissionCheck.Permissions perms = new PermissionCheck.Permissions();
            String permission = Manifest.permission.POST_NOTIFICATIONS;
            if (!PermissionCheck.hasPermissions(requireContext(), perms.NOTIFICATION_PERMISSIONS)) {
                Log.d(TAG, "Permission check: " + permission + " NOT GRANTED, requesting...");
                notificationPermissionLauncher.launch(permission);
                return true;
            }
            Log.d(TAG, "Permission check: " + permission + " GRANTED");
        }
        return false;
    }

    @Override
    public void onStart() {
        super.onStart();
        // Bind to AudioPlaybackService
        Intent intent = new Intent(getActivity(), AudioPlaybackService.class);
        requireActivity().bindService(intent, mConnection, Context.BIND_AUTO_CREATE);
    }

    @Override
    public void onStop() {
        if (isServiceBound) {
            requireActivity().unbindService(mConnection);
            isServiceBound = false;
        }
        super.onStop();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        // Clear view references
        autoStartCheckBox = null;
        fullScrollView = null;
        playButton = null;
        portInput = null;
        bufferHeadroomSpinner = null;
        serverInput = null;
        targetLatencySpinner = null;
        errorText = null;

        if (isServiceBound) {
            requireActivity().unbindService(mConnection);
            isServiceBound = false;
        }
        boundService = null;
    }

    // Helper method to format values as seconds
    private List<String> formatValuesAsSeconds(List<Long> values) {
        List<String> formattedValues = new ArrayList<>();
        for (Long value : values) {
            if (value >= 0) {
                formattedValues.add(String.format(Locale.getDefault(), "%.3fs", value / 1000000.0));
            } else {
                formattedValues.add("Default");
            }
        }
        return formattedValues;
    }

    private void updatePrefs(AudioPlaybackService service) {
        if (service == null) {
            //Log.e(TAG, "AudioPlaybackService is null in updatePrefs");
            return;
        }

        if (serverInput != null) {
            String serverPref = service.getServerPref();
            if (serverPref != null && !serverPref.isEmpty()) {
                serverInput.setText(serverPref);
            }
        }

        if (portInput != null) {
            int portPref = service.getPortPref();
            if (portPref > 0) {
                portInput.setText(String.valueOf(portPref));
            }
        }

        if (autoStartCheckBox != null) {
            autoStartCheckBox.setChecked(service.getAutostartPref());
        }

        if (bufferHeadroomSpinner != null) {
            setUpSpinner(bufferHeadroomSpinner, VALUES_BUFFER_HEADROOM, service.getBufferHeadroom(), DEFAULT_INDEX_BUFFER_HEADROOM);
        }

        if (targetLatencySpinner != null) {
            setUpSpinner(targetLatencySpinner, VALUES_TARGET_LATENCY, service.getTargetLatency(), DEFAULT_INDEX_TARGET_LATENCY);
        }
    }

    private void setUpSpinner(Spinner spinner, List<Long> values, long value, int defaultIndex) {
        int pos = values.indexOf(value);
        spinner.setSelection(pos >= 0 ? pos : defaultIndex);

        spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                int headroomIndex = bufferHeadroomSpinner.getSelectedItemPosition();
                int latencyIndex = targetLatencySpinner.getSelectedItemPosition();
                if (headroomIndex >= 0 && latencyIndex >= 0 && boundService != null) {
                    long headroomUsec = VALUES_BUFFER_HEADROOM.get(headroomIndex);
                    long latencyUsec = VALUES_TARGET_LATENCY.get(latencyIndex);
                    boundService.setBufferUsec(headroomUsec, latencyUsec);
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });
    }

    private void updatePlayStateInternal(@NonNull AudioPlayState playState) {
        playButton.setText(getPlayButtonText(playState));
        playButton.setEnabled(true);

        switch (playState) {
            case STOPPED:
                appendErrorText("Disconnected State", android.R.color.holo_orange_light);
                appendDashes();
                playButton.setEnabled(true);
                break;
            case STARTING:
                appendErrorText("Connection Starting", android.R.color.holo_green_dark);
                playButton.setEnabled(true);
                break;
            case BUFFERING:
                appendErrorText("Establishing Connection", android.R.color.holo_orange_light);
                playButton.setEnabled(true);
                break;
            case STARTED:
                appendErrorText("Everything is working fine! Enjoy!", android.R.color.holo_green_dark);
                appendDashes();
                playButton.setEnabled(true);
                break;
            case STOPPING:
                appendErrorText("Connection Disconnecting", android.R.color.holo_red_light);
                playButton.setEnabled(false);
                break;
        }

        if (boundService != null && boundService.getError() != null) {
            appendErrorText("An error occurred: " + boundService.getError().getMessage(), android.R.color.holo_red_dark);
            appendDashes();
        }
    }

    private String getPlayButtonText(@NonNull AudioPlayState playState) {
        switch (playState) {
            case STOPPED:
                return getString(R.string.btn_play);
            case STARTING:
                return getString(R.string.btn_starting);
            case BUFFERING:
                return getString(R.string.btn_buffering);
            case STARTED:
                return getString(R.string.btn_stop);
            case STOPPING:
                return getString(R.string.btn_stopping);
            default:
                return getString(R.string.btn_waiting);
        }
    }

    private void appendErrorText(String message, int colorId) {
        SpannableString spannable = new SpannableString(message + "\n");
        int color = ContextCompat.getColor(requireContext(), colorId);
        spannable.setSpan(new ForegroundColorSpan(color), 0, spannable.length(), 0);
        if (errorText != null) errorText.append(spannable);
    }

    private void appendDashes() {
        SpannableString dashes = new SpannableString("------------------\n");
        int purple = ContextCompat.getColor(requireContext(), android.R.color.holo_purple);
        dashes.setSpan(new ForegroundColorSpan(purple), 0, dashes.length(), 0);
        if (errorText != null) errorText.append(dashes);
    }

    public void play() {
        if (serverInput == null || portInput == null) {
            //Log.e(TAG, "UI elements are not initialized");
            return;
        }
        if (checkAndRequestNotificationPermission()) return;
        if (checkAndRequestAudioPermission()) return;
        String server = serverInput.getText().toString().trim();
        int port;
        try {
            port = Integer.parseInt(portInput.getText().toString());
        } catch (NumberFormatException e) {
            portInput.setError("Invalid port number");
            return;
        }
        // Clear any previous error messages
        portInput.setError(null);

        if (server.isEmpty()) {
            serverInput.setError("Server cannot be empty");
            return;
        }

        if (boundService != null) {
            // Log the server and port being used
            Log.d(TAG, "Attempting to play on server: " + server + " port: " + port);

            // Set preferences and start playback
            boundService.setPrefs(server, port, autoStartCheckBox.isChecked());
            boundService.play(server, port);
        } else {
            // Handle case where service is not bound
            errorText.setText(R.string.audio_service_not_bound);
            //Log.e(TAG, "Service not bound when attempting to play.");
        }
    }

    public void stop() {
        if (boundService != null) {
            boundService.stop();
        }
    }

    public void setError(Throwable error) {
        this.error = error;
        if (errorText != null) {
            errorText.setText(error.getMessage());
        }
        Log.e(TAG, "Error set: " + error.getMessage(), error);
    }
}
