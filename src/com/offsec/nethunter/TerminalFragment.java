package com.offsec.nethunter;

import android.annotation.SuppressLint;
import android.content.ComponentName;
import android.content.ClipboardManager;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.text.Editable;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.TextPaint;
import android.text.TextWatcher;
import android.text.style.BackgroundColorSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.text.style.UnderlineSpan;
import android.util.Log;
import android.util.TypedValue;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SearchView;
import androidx.core.content.ContextCompat;
import androidx.core.view.MenuProvider;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.DefaultItemAnimator;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;
import com.offsec.nethunter.pty.PtyNative;
import com.offsec.nethunter.service.TerminalService;
import com.offsec.nethunter.terminal.TerminalAdapter;
import com.offsec.nethunter.utils.NhPaths;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public class TerminalFragment extends Fragment implements MenuProvider {
    private static final String TAG = "TerminalFragment";
    private static final String ARG_ITEM_ID = "item_id";
    private static final String KEY_INITIAL_COMMAND = "initial_command";
    private static final boolean USE_PTY = true;
    private static final boolean USE_CHROOT_DIRECT = true;
    private static final int RING_MAX_LINES = 5000;
    private static final float MIN_TEXT_SP = 8f;
    private static final float MAX_TEXT_SP = 32f;
    private static final float DEFAULT_TEXT_SP = 12f;
    private static final String PREFS_NAME = "terminal_prefs";
    private static final String KEY_TEXT_SIZE = "text_size_sp";
    private static final String KEY_THEME_BG = "terminal_theme_bg";
    private static final String KEY_THEME_FG = "terminal_theme_fg";
    private static final String KEY_FORMAT_PRESET = "terminal_format_preset";
    private static final String KEY_LINE_SPACING_EXTRA = "terminal_line_spacing_extra";
    private static final String KEY_LINE_SPACING_MULT = "terminal_line_spacing_mult";
    private static final String KEY_PREF_SHELL = "preferred_shell";
    private static final String KEY_FIRST_RUN_SETUP_SHOWN = "first_run_setup_shown";
    private static final String KEY_PREF_INITIAL_CMD_TEXT = "initial_cmd_text";
    private static final String KEY_PREF_INITIAL_CMD_ENABLED = "initial_cmd_enabled";
    private static final String KEY_AUTO_PS1_ENABLED = "auto_ps1_enabled";
    private static final String KEY_PS1_STYLE = "ps1_style";
    private static final String KEY_PS1_CUSTOM = "ps1_custom";
    private static final String DEFAULT_HOSTNAME = "kali";
    private static final int PERSISTENT_BUFFER_SIZE = 100;
    private final List<CharSequence> persistentLines = new ArrayList<>();
    private TextInputEditText inputEdit;
    private RecyclerView terminalRecycler;
    private TerminalAdapter terminalAdapter;
    private View ctrlButton;
    private com.google.android.material.floatingactionbutton.FloatingActionButton fabGoBottom;
    private com.google.android.material.floatingactionbutton.FloatingActionButton fabCopySelected;
    private com.google.android.material.floatingactionbutton.FloatingActionButton fabFullscreen;
    private Process process;
    private volatile OutputStream outputStream;
    private volatile BufferedWriter writer;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final java.util.concurrent.ExecutorService ansiParserExecutor = 
        java.util.concurrent.Executors.newSingleThreadExecutor();
    private Thread outputThread;
    private Thread errorThread;
    private final List<String> commandHistory = new ArrayList<>();
    private int historyIndex = -1;
    private String pendingCurrentLine = "";
    private int defaultFgColor;
    private int currentFgColor;
    private final int defaultBgColor = 0x00000000;
    private int currentBgColor = 0x00000000;
    private boolean currentBold = false;
    private boolean currentUnderline = false;
    private String ansiCarry = "";
    private volatile int ptyFd = -1;
    private volatile int ptyPid = -1;
    private volatile ParcelFileDescriptor ptyPfd;
    private volatile FileInputStream ptyIn;
    private volatile FileOutputStream ptyOut;
    private Thread ptyReadThread;
    private SpannableStringBuilder currentLine = new SpannableStringBuilder();
    private int currentLineSegmentStart = 0;
    private int cursorColumn = 0;
    private ScaleGestureDetector scaleDetector;
    private boolean ctrlSticky = false;
    private boolean suppressTextWatcher = false;
    private int pendingInsertStart = -1;
    private int pendingInsertCount = 0;
    private char pendingInsertChar;
    private TerminalService boundService;
    private boolean serviceBound = false;
    private int serviceSessionId = -1;
    private boolean isFullscreen = false;
    private MenuItem fullscreenMenuItem;

    private static class ThemePreset {
        final String name; final int bg; final int fg;
        ThemePreset(String n, int b, int f) { name = n; bg = b; fg = f; }
    }

    private static final ThemePreset[] THEME_PRESETS = new ThemePreset[] {
            new ThemePreset("Classic Dark", Color.parseColor("#121212"), Color.parseColor("#ECEFF1")),
            new ThemePreset("Solarized Dark", Color.parseColor("#002b36"), Color.parseColor("#93a1a1")),
            new ThemePreset("Solarized Light", Color.parseColor("#fdf6e3"), Color.parseColor("#657b83")),
            new ThemePreset("Dracula", Color.parseColor("#282a36"), Color.parseColor("#f8f8f2")),
            new ThemePreset("One Dark", Color.parseColor("#282c34"), Color.parseColor("#abb2bf")),
            new ThemePreset("High Contrast", Color.parseColor("#000000"), Color.parseColor("#FFFFFF")),
            new ThemePreset("Matrix", Color.parseColor("#000000"), Color.parseColor("#00FF00")),
            new ThemePreset("Kali Linux", Color.parseColor("#000000"), Color.parseColor("#DC143C"))
    };

    /**
     * PS1 prompt format presets for different shell types.
     * Provides clean, readable prompt formats with minimal ANSI escape sequences.
     */
    private static class PS1Preset {
        final String name;
        final String bashFormat;
        final String zshFormat;
        final String shFormat;

        PS1Preset(String name, String bashFormat, String zshFormat, String shFormat) {
            this.name = name;
            this.bashFormat = bashFormat;
            this.zshFormat = zshFormat;
            this.shFormat = shFormat;
        }

        /**
         * Minimal prompt: just the prompt symbol ($ or #)
         */
        static final PS1Preset MINIMAL = new PS1Preset(
            "Minimal",
            "PS1='\\$ '",
            "PS1='%# '",
            "PS1='$ '"
        );

        /**
         * Standard prompt: user@host:dir$
         * Uses green for user@host, blue for directory
         */
        static final PS1Preset STANDARD = new PS1Preset(
            "Standard",
            "PS1='\\[\\e[1;32m\\]\\u@\\h\\[\\e[0m\\]:\\[\\e[1;34m\\]\\w\\[\\e[0m\\]\\$ '",
            "PS1='%F{green}%n@%m%f:%F{blue}%~%f%# '",
            "PS1='\\u@\\h:\\w\\$ '"
        );

        /**
         * Full prompt: user@host:dir [time]$
         * Includes timestamp for command tracking
         */
        static final PS1Preset FULL = new PS1Preset(
            "Full",
            "PS1='\\[\\e[1;32m\\]\\u@\\h\\[\\e[0m\\]:\\[\\e[1;34m\\]\\w\\[\\e[0m\\] [\\t]\\$ '",
            "PS1='%F{green}%n@%m%f:%F{blue}%~%f [%*]%# '",
            "PS1='\\u@\\h:\\w [\\t]\\$ '"
        );
    }

    public static TerminalFragment newInstance(int itemId) {
        TerminalFragment fragment = new TerminalFragment();
        Bundle args = new Bundle();
        args.putInt(ARG_ITEM_ID, itemId);
        fragment.setArguments(args);
        return fragment;
    }

    public static TerminalFragment newInstanceWithCommand(int itemId, @Nullable String initialCmd) {
        TerminalFragment fragment = new TerminalFragment();
        Bundle args = new Bundle();
        args.putInt(ARG_ITEM_ID, itemId);
        if (initialCmd != null && !initialCmd.trim().isEmpty()) {
            args.putString(KEY_INITIAL_COMMAND, initialCmd);
        }
        fragment.setArguments(args);
        return fragment;
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        NhPaths.getInstance(requireContext());
        requireActivity().addMenuProvider(this);
    }

    @SuppressLint("ClickableViewAccessibility")
    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_terminal, container, false);
        terminalRecycler = view.findViewById(R.id.terminal_recycler);
        terminalRecycler.setLayoutManager(new LinearLayoutManager(getContext()));
        terminalAdapter = new TerminalAdapter(RING_MAX_LINES);
        terminalAdapter.setTextSizeSp(loadPersistedTextSize());
        loadAndApplyPersistedFormat(terminalAdapter);
        terminalRecycler.setAdapter(terminalAdapter);

        RecyclerView.ItemAnimator itemAnimator = terminalRecycler.getItemAnimator();
        if (itemAnimator instanceof DefaultItemAnimator) {
            DefaultItemAnimator da = (DefaultItemAnimator) itemAnimator;
            da.setSupportsChangeAnimations(false);
            da.setChangeDuration(0);
        }

        // Batch-populate any persisted lines to leverage AsyncListDiffer diffs
        if (!persistentLines.isEmpty()) {
            terminalAdapter.addLines(persistentLines, terminalRecycler);
        }

        inputEdit = view.findViewById(R.id.input_edit);
        loadAndApplyPersistedTheme();

        fabGoBottom = view.findViewById(R.id.fab_go_bottom);
        if (fabGoBottom != null) {
            fabGoBottom.hide();
            fabGoBottom.setOnClickListener(v -> {
                if (terminalAdapter != null && terminalRecycler != null) {
                    int count = terminalAdapter.getItemCount();
                    if (count > 0) terminalRecycler.smoothScrollToPosition(count - 1);
                }
                fabGoBottom.hide();
            });
        }

        fabCopySelected = view.findViewById(R.id.fab_copy_terminal);
        if (fabCopySelected != null) {
            fabCopySelected.hide();
            fabCopySelected.setOnClickListener(v -> {
                copySelectedLinesToClipboard();
                if (terminalAdapter != null) terminalAdapter.clearSelection();
                fabCopySelected.hide();
                if (terminalRecycler != null) terminalRecycler.requestFocus();
            });
        }

        fabFullscreen = view.findViewById(R.id.fab_fullscreen);
        if (fabFullscreen != null) {
            fabFullscreen.setOnClickListener(v -> toggleFullscreen());
            updateFullscreenFabIcon();
            makeFabDraggable(fabFullscreen);
        }

        terminalRecycler.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override public void onScrolled(@NonNull RecyclerView rv, int dx, int dy) { updateFabVisibilityByScroll(dy); }
            @Override public void onScrollStateChanged(@NonNull RecyclerView rv, int newState) { updateFabVisibilityByScroll(0); }
        });
        updateFabVisibilityByScroll(0);

        // Selection listener: show copy FAB when there are selected lines
        if (terminalAdapter != null) {
            terminalAdapter.setSelectionListener(new com.offsec.nethunter.terminal.TerminalAdapter.SelectionListener() {
                @Override
                public void onSelectionChanged(Set<Integer> selectedLines) {
                    if (fabCopySelected == null) return;
                    if (selectedLines != null && !selectedLines.isEmpty()) fabCopySelected.show(); else fabCopySelected.hide();
                }

                @Override
                public void onLineLongClicked(int position, CharSequence text) {
                    // No-op for now; long-click already toggles selection in adapter. Could show a tooltip later.
                }
            });
        }

        // Clear selection when touching the recycler (so tapping outside selection cancels it)
        terminalRecycler.setOnTouchListener((v, event) -> {
            if (terminalAdapter != null && !terminalAdapter.getSelectedLines().isEmpty()) {
                if (event.getAction() == android.view.MotionEvent.ACTION_DOWN) {
                    terminalAdapter.clearSelection();
                    if (fabCopySelected != null) fabCopySelected.hide();
                }
            }
            // allow normal processing (clicks/scrolls) to continue
            return false;
        });

        TextInputLayout inputLayout = view.findViewById(R.id.input_layout);
        if (inputLayout != null) {
            inputLayout.setEndIconOnClickListener(v -> sendCommand());
            boolean hasInitial = inputEdit != null && inputEdit.getText() != null && !inputEdit.getText().toString().trim().isEmpty();
            inputLayout.setEndIconVisible(hasInitial);
        }

        View btnTab = view.findViewById(R.id.btn_tab);
        View btnUp = view.findViewById(R.id.btn_up);
        View btnDown = view.findViewById(R.id.btn_down);
        View btnEsc = view.findViewById(R.id.btn_esc);
        ctrlButton = view.findViewById(R.id.btn_ctrl);
        View btnClear = view.findViewById(R.id.terminal_cmd_clear);
        if (btnClear != null) btnClear.setOnClickListener(v -> clearTerminal());

        updateCtrlButtonState();
        if (inputEdit != null) { defaultFgColor = inputEdit.getCurrentTextColor(); currentFgColor = defaultFgColor; }

        scaleDetector = new ScaleGestureDetector(requireContext(), new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override public boolean onScale(@NonNull ScaleGestureDetector detector) {
                if (terminalAdapter == null) return false;
                float current = terminalAdapter.getTextSizeSp();
                float factor = Math.max(0.5f, Math.min(2f, detector.getScaleFactor()));
                float newSize = clamp(current * factor);
                if (Math.abs(newSize - current) >= 0.2f) applyTextSize(newSize);
                return true;
            }
        });
        terminalRecycler.setOnTouchListener((v, event) -> {
            if (scaleDetector != null) scaleDetector.onTouchEvent(event);
            boolean scaling = scaleDetector != null && scaleDetector.isInProgress();
            if (!scaling && event.getAction() == android.view.MotionEvent.ACTION_UP) v.performClick();
            return scaling;
        });

        if (inputEdit != null) {
            inputEdit.setOnKeyListener((v, keyCode, event) -> {
                if (event.getAction() == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_ENTER) { sendCommand(); return true; }
                if (event.getAction() == KeyEvent.ACTION_DOWN && (event.isCtrlPressed() || ctrlSticky)) {
                    int ctrlCode = controlCodeForKeyCode(keyCode);
                    if (ctrlCode > 0) {
                        sendControlCode(ctrlCode);
                        if (ctrlSticky) { ctrlSticky = false; updateCtrlVisualState(); }
                        return true;
                    }
                }
                return false;
            });
            if (inputLayout != null) {
                inputEdit.addTextChangedListener(new TextWatcher() {
                    @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
                    @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                        if (ctrlSticky && count > 0) {
                            pendingInsertStart = start;
                            pendingInsertCount = count;
                            try { pendingInsertChar = s.charAt(start + count - 1); } catch (Throwable ignored) { pendingInsertChar = 0; }
                        }
                    }
                    @Override public void afterTextChanged(Editable s) {
                        inputLayout.setEndIconVisible(s != null && !s.toString().trim().isEmpty());
                        if (s == null) return;
                        if (!ctrlSticky || pendingInsertCount <= 0 || suppressTextWatcher) return;
                        int code = controlCodeForChar(pendingInsertChar);
                        if (code > 0) sendControlCode(code);
                        ctrlSticky = false; updateCtrlVisualState();
                        try {
                            suppressTextWatcher = true;
                            int start = Math.max(0, Math.min(s.length(), pendingInsertStart));
                            int end = Math.max(start, Math.min(s.length(), start + pendingInsertCount));
                            if (end > start) s.delete(start, end);
                        } finally {
                            suppressTextWatcher = false;
                            pendingInsertStart = -1; pendingInsertCount = 0; pendingInsertChar = 0;
                        }
                    }
                });
            }
        }
        if (btnTab != null) btnTab.setOnClickListener(v -> insertAtCursor());
        if (btnUp != null) btnUp.setOnClickListener(v -> navigateHistory(-1));
        if (btnDown != null) btnDown.setOnClickListener(v -> navigateHistory(1));
        if (btnEsc != null) btnEsc.setOnClickListener(v -> sendControlCode(27));
        if (ctrlButton != null) ctrlButton.setOnClickListener(v -> { ctrlSticky = !ctrlSticky; updateCtrlVisualState(); });

        Bundle args = getArguments();
        if (args != null && args.containsKey(KEY_INITIAL_COMMAND)) {
            final String initCmd = args.getString(KEY_INITIAL_COMMAND);
            if (initCmd != null && !initCmd.trim().isEmpty() && !"uname -a".equals(initCmd.trim())) {
                handler.postDelayed(() -> sendLine(initCmd), 650);
            }
        }
        terminalRecycler.post(this::updatePtyWindowSize);
        // Don't spin up legacy PTY eagerly; the service will own sessions. Keep legacy as fallback.
        // if (ptyOut == null) startTerminal();

        // Start and bind to TerminalService for background sessions
        Intent svc = new Intent(requireContext(), TerminalService.class);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ContextCompat.startForegroundService(requireContext(), svc);
            } else {
                requireContext().startService(svc);
            }
        } catch (Throwable ignored) {}
        requireContext().bindService(svc, serviceConnection, Context.BIND_AUTO_CREATE);

        // Show first-run setup dialog once
        handler.postDelayed(this::maybeShowFirstRunSetupDialog, 500);
        // Run saved initial command on open if enabled
        handler.postDelayed(() -> maybeRunSavedInitialCommand(getArguments()), 700);
        return view;
    }

    private void updateCtrlButtonState() {
        if (ctrlButton == null) return;
        boolean enabled = (serviceBound && serviceSessionId > 0) || (ptyOut != null || outputStream != null);
        ctrlButton.setEnabled(enabled);
        ctrlButton.setAlpha(enabled ? (ctrlSticky ? 1.0f : 0.95f) : 0.5f);
    }

    private void updateCtrlVisualState() {
        if (ctrlButton == null) return;
        ctrlButton.setSelected(ctrlSticky);
        ctrlButton.setAlpha(ctrlButton.isEnabled() ? (ctrlSticky ? 1.0f : 0.95f) : 0.5f);
    }

    // Tab completion state - synchronized access required
    private final Object completionLock = new Object();
    private volatile String completionOutput = "";
    private volatile boolean waitingForCompletion = false;
    private String lastCompletionInput = "";
    private Thread completionThread;

    private void insertAtCursor() {
        if (inputEdit == null) return;
        
        String currentText = inputEdit.getText() != null ? inputEdit.getText().toString() : "";
        
        // If empty or not ready, just insert tab
        if (currentText.trim().isEmpty() || !isShellReady()) {
            insertTabCharacter();
            return;
        }
        
        // Perform tab completion
        performTabCompletion(currentText);
    }

    private void insertTabCharacter() {
        if (inputEdit == null) return;
        int start = inputEdit.getSelectionStart();
        int end = inputEdit.getSelectionEnd();
        Editable editable = inputEdit.getText();
        if (editable == null) return;
        if (start < 0) start = editable.length(); 
        if (end < 0) end = start;
        editable.replace(Math.min(start, end), Math.max(start, end), "\t");
        int newPos = Math.min(start, end) + 1; 
        inputEdit.setSelection(newPos);
    }

    private void performTabCompletion(String input) {
        synchronized (completionLock) {
            // Prevent multiple concurrent completions
            if (waitingForCompletion) return;
            
            // Parse input to get word to complete
            String[] words = input.trim().split("\\s+");
            if (words.length == 0) return;
            
            String wordToComplete = words[words.length - 1];
            String prefix = input.substring(0, input.lastIndexOf(wordToComplete));
            boolean isFirstWord = words.length == 1;
            
            // Build compgen command
            String compgenCmd;
            if (isFirstWord) {
                // Complete command names
                compgenCmd = String.format("compgen -c '%s' 2>/dev/null", wordToComplete.replace("'", "'\\''"));
            } else {
                // Complete file/directory names
                compgenCmd = String.format("compgen -f '%s' 2>/dev/null", wordToComplete.replace("'", "'\\''"));
            }

            // Execute command and capture output without visible markers
            String fullCmd = "{ " + compgenCmd + "; } 2>/dev/null\n";
            
            // Cancel any existing completion thread
            if (completionThread != null && completionThread.isAlive()) {
                waitingForCompletion = false;
                completionThread.interrupt();
                try {
                    completionThread.join(100); // Wait briefly for thread to finish
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            }
            
            // Set up completion state
            waitingForCompletion = true;
            completionOutput = "";
            lastCompletionInput = input;
            
            // Create a temporary listener to capture completion output
            final String finalPrefix = prefix;
            final String finalWord = wordToComplete;
            final long startTime = System.currentTimeMillis();
            
            completionThread = new Thread(() -> {
                try {
                    // Clear any previous completion output
                    synchronized (completionLock) {
                        completionOutput = "";
                    }
                    
                    // Send completion command
                    if (serviceBound && serviceSessionId > 0) {
                        boundService.send(serviceSessionId, fullCmd);
                    } else if (ptyOut != null) {
                        writePty(fullCmd);
                    } else if (writer != null) {
                        synchronized (writer) {
                            writer.write(fullCmd);
                            writer.flush();
                        }
                    }
                    
                    // Wait for completion output (with shorter timeout)
                    while (waitingForCompletion && !Thread.currentThread().isInterrupted() && (System.currentTimeMillis() - startTime) < 1000) {
                        String currentOutput;
                        synchronized (completionLock) {
                            currentOutput = completionOutput;
                        }
                        
                        // Check if we have output and it looks complete (ends with newline or prompt)
                        if (!currentOutput.trim().isEmpty() && 
                            (currentOutput.endsWith("\n") || currentOutput.contains("$") || currentOutput.contains("#"))) {
                            // Process completions
                            processCompletions(currentOutput.trim(), finalPrefix, finalWord);
                            synchronized (completionLock) {
                                waitingForCompletion = false;
                            }
                            break;
                        }
                        Thread.sleep(50);
                    }
                    
                    synchronized (completionLock) {
                        if (waitingForCompletion && !Thread.currentThread().isInterrupted()) {
                            // Timeout - try to process whatever output we have
                            String currentOutput = completionOutput.trim();
                            if (!currentOutput.isEmpty()) {
                                processCompletions(currentOutput, finalPrefix, finalWord);
                            }
                            waitingForCompletion = false;
                        }
                    }
                } catch (InterruptedException e) {
                    // Thread was interrupted, clean exit
                    synchronized (completionLock) {
                        waitingForCompletion = false;
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Error performing tab completion", e);
                    synchronized (completionLock) {
                        waitingForCompletion = false;
                    }
                }
            }, "tab-completion");
            completionThread.start();
        }
    }

    private void processCompletions(String output, String prefix, String wordToComplete) {
        String[] completions = output.trim().split("\n");
        
        // Filter out empty lines and the command itself
        List<String> validCompletions = new ArrayList<>();
        for (String completion : completions) {
            String trimmed = completion.trim();
            if (!trimmed.isEmpty() && !trimmed.equals(wordToComplete) && !trimmed.startsWith("compgen")) {
                validCompletions.add(trimmed);
            }
        }
        
        handler.post(() -> {
            if (!isAdded() || inputEdit == null) return;
            
            if (validCompletions.isEmpty()) {
                // No completions found
                Toast.makeText(requireContext(), "No completions found", Toast.LENGTH_SHORT).show();
            } else if (validCompletions.size() == 1) {
                // Single completion - auto-complete
                String completed = validCompletions.get(0);
                inputEdit.setText(prefix + completed + " ");
                inputEdit.setSelection(inputEdit.getText().length());
            } else {
                // Multiple completions - find common prefix and show options
                String commonPrefix = findCommonPrefix(validCompletions);
                
                if (commonPrefix.length() > wordToComplete.length()) {
                    // There's a common prefix longer than what user typed - complete to that
                    inputEdit.setText(prefix + commonPrefix);
                    inputEdit.setSelection(inputEdit.getText().length());
                }
                
                // Show completion options in a dialog
                showCompletionDialog(validCompletions, prefix);
            }
        });
    }

    private String findCommonPrefix(List<String> completions) {
        if (completions.isEmpty()) return "";
        if (completions.size() == 1) return completions.get(0);
        
        String first = completions.get(0);
        int prefixLen = 0;
        
        for (int i = 0; i < first.length(); i++) {
            char c = first.charAt(i);
            boolean allMatch = true;
            
            for (String completion : completions) {
                if (i >= completion.length() || completion.charAt(i) != c) {
                    allMatch = false;
                    break;
                }
            }
            
            if (allMatch) {
                prefixLen = i + 1;
            } else {
                break;
            }
        }
        
        return first.substring(0, prefixLen);
    }

    private void showCompletionDialog(List<String> completions, String prefix) {
        if (!isAdded()) return;
        
        AlertDialog.Builder builder = new AlertDialog.Builder(requireContext());
        builder.setTitle("Tab Completions (" + completions.size() + " options)");
        
        String[] items = completions.toArray(new String[0]);
        builder.setItems(items, (dialog, which) -> {
            if (inputEdit != null) {
                String selected = items[which];
                inputEdit.setText(prefix + selected + " ");
                inputEdit.setSelection(inputEdit.getText().length());
                inputEdit.requestFocus();
            }
        });
        
        builder.setNegativeButton("Cancel", null);
        builder.show();
    }

    private boolean isShellReady() {
        return (serviceBound && serviceSessionId > 0) || (ptyOut != null) || (writer != null);
    }

    private void navigateHistory(int direction) {
        if (commandHistory.isEmpty() || inputEdit == null) return;
        if (historyIndex == -1) { pendingCurrentLine = inputEdit.getText() != null ? inputEdit.getText().toString() : ""; historyIndex = commandHistory.size(); }
        historyIndex = Math.max(0, Math.min(commandHistory.size(), historyIndex + direction));
        setInputText(historyIndex == commandHistory.size() ? pendingCurrentLine : commandHistory.get(historyIndex));
    }

    private void setInputText(String text) { if (inputEdit == null) return; inputEdit.setText(text); inputEdit.setSelection(text.length()); }

    private void recordHistory(String command) {
        if (command == null) return; String trimmed = command.trim(); if (trimmed.isEmpty()) return;
        if (!commandHistory.isEmpty() && commandHistory.get(commandHistory.size() - 1).equals(trimmed)) { historyIndex = -1; return; }
        commandHistory.add(trimmed); historyIndex = -1;
    }

    private void startTerminal() {
        if (USE_PTY && PtyNative.isLoaded()) startTerminalPty();
        else { if (USE_PTY && !PtyNative.isLoaded()) Log.d(TAG, "[!] native-lib not loaded; falling back to non-PTY shell."); startTerminalProcess(); }
    }

    private void startTerminalProcess() {
        Log.d(TAG, "Starting terminal process");
        new Thread(() -> {
            try {
                process = Runtime.getRuntime().exec("su -mm");
                outputStream = process.getOutputStream();
                writer = new BufferedWriter(new OutputStreamWriter(outputStream));
                final InputStream stdout = process.getInputStream();
                final InputStream stderr = process.getErrorStream();
                outputThread = new Thread(() -> readStream(stdout, false), "term-out");
                errorThread = new Thread(() -> readStream(stderr, true), "term-err");
                outputThread.start(); errorThread.start();
                
                // Send entry command to enter chroot
                String init = getEntryCmd() + "\n";
                writer.write(init); writer.flush();
                
                // Wait for shell to be ready before sending init commands
                handler.postDelayed(this::initializeShellEnvironment, 500);
                
                handler.post(this::updateCtrlButtonState);
            } catch (IOException e) { Log.e(TAG, "Failed to start terminal", e); }
        }).start();
    }

    private void startTerminalPty() {
        Log.d(TAG, "Starting PTY terminal");
        new Thread(() -> {
            try {
                int[] res;
                if (USE_CHROOT_DIRECT && PtyNative.isLoaded()) {
                    if (!isChrootAvailable()) { Log.d(TAG, "[!] Chroot not available; falling back to generic PTY shell."); res = PtyNative.openPtyShell(); }
                    else {
                        String resolvedShell = resolvePreferredShell();
                        String chrootCmd = buildChrootShellCommand(resolvedShell);
                        Log.d(TAG, "Launching chroot command: " + chrootCmd);
                        res = PtyNative.openPtyShellExec(chrootCmd);
                        if (res == null) { Log.d(TAG, "[!] Direct chroot launch failed, fallback to generic PTY shell."); res = PtyNative.openPtyShell(); }
                        else { Log.d(TAG, "[+] Chroot shell started (direct) using shell: " + resolvedShell); }
                    }
                } else { res = PtyNative.openPtyShell(); }
                if (res == null || res.length < 2) { Log.d(TAG, "[!] PTY open failed, falling back to non-PTY shell."); startTerminalProcess(); return; }
                ptyFd = res[0]; ptyPid = res[1];
                ptyPfd = ParcelFileDescriptor.adoptFd(ptyFd);
                ptyIn = new FileInputStream(ptyPfd.getFileDescriptor());
                ptyOut = new FileOutputStream(ptyPfd.getFileDescriptor());
                ptyReadThread = new Thread(() -> readStream(ptyIn, false), "pty-reader"); ptyReadThread.start();
                
                // Send a newline to trigger prompt
                writePty("\n");
                
                // Wait for shell to be ready before sending init commands
                handler.postDelayed(this::initializeShellEnvironment, 500);
                
                handler.post(this::updateCtrlButtonState);
                scheduleInitialWindowSizeUpdate();
            } catch (Exception e) {
                Log.e(TAG, "PTY startup failed", e); startTerminalProcess();
            }
        }).start();
    }

    private void scheduleInitialWindowSizeUpdate() { if (terminalRecycler == null) return; terminalRecycler.post(this::updatePtyWindowSize); }

    private void updatePtyWindowSize() {
        if (serviceBound && serviceSessionId > 0) {
            // compute cols/rows and send to service
            TextPaint tp = new TextPaint(); tp.setTypeface(Typeface.MONOSPACE);
            float activeSp = (terminalAdapter != null) ? terminalAdapter.getTextSizeSp() : 12f;
            tp.setTextSize(spToPx(activeSp));
            float charWidth = tp.measureText("M"); float lineHeight = tp.getFontMetrics().bottom - tp.getFontMetrics().top;
            int w = terminalRecycler.getWidth(); int h = terminalRecycler.getHeight();
            if (w <= 0 || h <= 0 || charWidth <= 0 || lineHeight <= 0) return;
            int cols = Math.max(20, (int)(w / charWidth)); int rows = Math.max(5, (int)(h / lineHeight));
            try { boundService.resizePty(serviceSessionId, cols, rows); } catch (Throwable ignored) {}
            return;
        }
        // legacy path
        if (!USE_PTY || ptyFd < 0 || !PtyNative.isLoaded() || terminalRecycler == null) return;
        TextPaint tp = new TextPaint(); tp.setTypeface(Typeface.MONOSPACE);
        float activeSp = (terminalAdapter != null) ? terminalAdapter.getTextSizeSp() : 12f;
        tp.setTextSize(spToPx(activeSp));
        float charWidth = tp.measureText("M"); float lineHeight = tp.getFontMetrics().bottom - tp.getFontMetrics().top;
        int w = terminalRecycler.getWidth(); int h = terminalRecycler.getHeight();
        if (w <= 0 || h <= 0 || charWidth <= 0 || lineHeight <= 0) return;
        int cols = Math.max(20, (int)(w / charWidth)); int rows = Math.max(5, (int)(h / lineHeight));
        try { PtyNative.setWindowSize(ptyFd, cols, rows); } catch (Throwable ignored) {}
    }

    private float spToPx(float sp) { return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sp, requireContext().getResources().getDisplayMetrics()); }

    private int controlCodeForKeyCode(int keyCode) {
        if (keyCode >= KeyEvent.KEYCODE_A && keyCode <= KeyEvent.KEYCODE_Z) {
            int letterIndex = keyCode - KeyEvent.KEYCODE_A; // 0..25
            char ch = (char) ('A' + letterIndex);
            return controlCodeForChar(ch);
        }
        return 0;
    }

    private int controlCodeForChar(char ch) {
        if (ch >= 'a' && ch <= 'z') ch = (char) (ch - 'a' + 'A');
        if (ch >= 'A' && ch <= 'Z') {
            return (ch & 0x1F);
        }
        return 0;
    }

    private void sendControlCode(int code) {
        if (code <= 0 || code > 31) return;
        if (serviceBound && serviceSessionId > 0) {
            boundService.sendControl(serviceSessionId, code);
            return;
        }
        new Thread(() -> {
            try {
                if (ptyOut != null) { ptyOut.write(new byte[]{(byte) code}); ptyOut.flush(); }
                else if (outputStream != null) { try { outputStream.write(code); outputStream.flush(); } catch (IOException ignored) {} }
                else { Log.d(TAG, "[!] Shell not ready for control code."); }
            } catch (IOException e) { Log.e(TAG, "Failed sending control code " + code, e); }
        }).start();
    }

    private void applyThemeColors(int bgColor, int fgColor) { applyThemeColors(bgColor, fgColor, true); }

    private void applyThemeColors(int bgColor, int fgColor, boolean persist) {
        if (terminalRecycler != null) terminalRecycler.setBackgroundColor(bgColor);
        if (terminalAdapter != null) terminalAdapter.setBaseTextColor(fgColor);
        defaultFgColor = fgColor; currentFgColor = fgColor; currentBgColor = 0x00000000; currentBold = false; currentUnderline = false;
        if (inputEdit != null) inputEdit.setTextColor(fgColor);
        if (persist) { SharedPreferences prefs = requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE); prefs.edit().putInt(KEY_THEME_BG, bgColor).putInt(KEY_THEME_FG, fgColor).apply(); }
    }

    private void loadAndApplyPersistedTheme() {
        SharedPreferences prefs = requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        int defBg = Color.parseColor("#121212");
        int defFg = (inputEdit != null) ? inputEdit.getCurrentTextColor() : Color.parseColor("#ECEFF1");
        int bg = prefs.getInt(KEY_THEME_BG, defBg); int fg = prefs.getInt(KEY_THEME_FG, defFg);
        applyThemeColors(bg, fg);
    }

    private void loadAndApplyPersistedFormat(TerminalAdapter adapter) {
        if (adapter == null) return;
        SharedPreferences prefs = requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        float extra = prefs.getFloat(KEY_LINE_SPACING_EXTRA, 0f); float mult = prefs.getFloat(KEY_LINE_SPACING_MULT, 1.0f);
        adapter.setLineSpacing(extra, mult);
    }

    private void persistFormat(String presetName, float lineExtra, float lineMult) {
        SharedPreferences prefs = requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        prefs.edit().putString(KEY_FORMAT_PRESET, presetName).putFloat(KEY_LINE_SPACING_EXTRA, lineExtra).putFloat(KEY_LINE_SPACING_MULT, lineMult).apply();
    }

    private float dp(float v) { return v * requireContext().getResources().getDisplayMetrics().density; }

    private void showThemePicker() {
        if (!isAdded()) return;
        LayoutInflater inflater = LayoutInflater.from(requireContext());
        View content = inflater.inflate(R.layout.dialog_terminal_theme, (ViewGroup) getView(), false);
        final ChipGroup chipsThemes = content.findViewById(R.id.chips_themes);
        final ChipGroup chipsFormat = content.findViewById(R.id.chips_format);
        final MaterialCardView previewCard = content.findViewById(R.id.preview_card);
        final TextView previewTitle = content.findViewById(R.id.preview_title);
        final TextView previewL1 = content.findViewById(R.id.preview_line1);
        final TextView previewL2 = content.findViewById(R.id.preview_line2);
        final TextView previewL3 = content.findViewById(R.id.preview_line3);
        final MaterialButton btnReset = content.findViewById(R.id.btn_reset);
        final MaterialButton btnCancel = content.findViewById(R.id.btn_cancel);
        final MaterialButton btnApply = content.findViewById(R.id.btn_apply);

        final List<Chip> themeChips = new ArrayList<>();
        int[] themeChipIds = {R.id.chip_theme_0, R.id.chip_theme_1, R.id.chip_theme_2, R.id.chip_theme_3, R.id.chip_theme_4, R.id.chip_theme_5, R.id.chip_theme_6, R.id.chip_theme_7};
        for (int i = 0; i < THEME_PRESETS.length; i++) {
            Chip chip = content.findViewById(themeChipIds[i]);
            chip.setCheckable(true);
            chip.setChipIconResource(R.drawable.ic_palette); chip.setChipIconTint(android.content.res.ColorStateList.valueOf(THEME_PRESETS[i].fg));
            chip.setTag(i); themeChips.add(chip);
        }

        SharedPreferences prefs = requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        int defBg = Color.parseColor("#121212");
        int defFg = (inputEdit != null) ? inputEdit.getCurrentTextColor() : Color.parseColor("#ECEFF1");
        int curBg = prefs.getInt(KEY_THEME_BG, defBg); int curFg = prefs.getInt(KEY_THEME_FG, defFg);
        boolean matched = false;
        for (int i = 0; i < THEME_PRESETS.length; i++) {
            if (THEME_PRESETS[i].bg == curBg && THEME_PRESETS[i].fg == curFg) { themeChips.get(i).setChecked(true); matched = true; break; }
        }
        if (!matched && !themeChips.isEmpty()) { themeChips.get(0).setChecked(true); }

        String fmtPref = prefs.getString(KEY_FORMAT_PRESET, "Minimal");
        int fmtChipId = R.id.chip_format_compact;
        if ("Minimal".equalsIgnoreCase(fmtPref)) fmtChipId = R.id.chip_format_minimal;
        else if ("Comfortable".equalsIgnoreCase(fmtPref)) fmtChipId = R.id.chip_format_comfortable;
        else if ("Large".equalsIgnoreCase(fmtPref)) fmtChipId = R.id.chip_format_large;
        chipsFormat.check(fmtChipId);

        final int originalBg = curBg; final int originalFg = curFg;
        final float originalSize = (terminalAdapter != null) ? terminalAdapter.getTextSizeSp() : DEFAULT_TEXT_SP;
        final float originalExtra = prefs.getFloat(KEY_LINE_SPACING_EXTRA, 0f);
        final float originalMult = prefs.getFloat(KEY_LINE_SPACING_MULT, 1.0f);
        final boolean[] applied = new boolean[]{false};

        final Runnable updatePreview = () -> {
            int themeIdx = 0; int checkedId = chipsThemes.getCheckedChipId();
            if (checkedId != View.NO_ID) { Chip c = content.findViewById(checkedId); if (c != null && c.getTag() instanceof Integer) themeIdx = (Integer) c.getTag(); }
            ThemePreset sel = THEME_PRESETS[themeIdx];
            String fmt = "Compact"; int checkedFmt = chipsFormat.getCheckedChipId();
            if (checkedFmt == R.id.chip_format_minimal) fmt = "Minimal";
            else if (checkedFmt == R.id.chip_format_comfortable) fmt = "Comfortable";
            else if (checkedFmt == R.id.chip_format_large) fmt = "Large";
            previewCard.setCardBackgroundColor(sel.bg);
            int fg = sel.fg; previewTitle.setTextColor(fg); previewL1.setTextColor(fg); previewL2.setTextColor(fg); previewL3.setTextColor(fg);
            float sizeSp; float extraPx; float mult;
            switch (fmt) {
                case "Minimal": sizeSp = MIN_TEXT_SP; extraPx = dp(0f); mult = 1.0f; break;
                case "Comfortable": sizeSp = 14f; extraPx = dp(2f); mult = 1.08f; break;
                case "Large": sizeSp = 16f; extraPx = dp(4f); mult = 1.12f; break;
                default: sizeSp = 12f; extraPx = dp(0f); mult = 1.0f; break;
            }
            previewL1.setTextSize(sizeSp); previewL2.setTextSize(sizeSp); previewL3.setTextSize(sizeSp);
            previewL1.setLineSpacing(extraPx, mult); previewL2.setLineSpacing(extraPx, mult); previewL3.setLineSpacing(extraPx, mult);
            applyThemeColors(sel.bg, sel.fg, false); applyFormatPreset(fmt, false);
        };

        chipsThemes.setOnCheckedStateChangeListener((group, checkedIds) -> updatePreview.run());
        chipsFormat.setOnCheckedStateChangeListener((group, checkedId) -> updatePreview.run());
        updatePreview.run();

        final BottomSheetDialog dialog = new BottomSheetDialog(requireContext(), com.google.android.material.R.style.ThemeOverlay_Material3_BottomSheetDialog);
        dialog.setContentView(content);

        btnReset.setOnClickListener(v -> { if (!themeChips.isEmpty()) { for (Chip c : themeChips) c.setChecked(false); themeChips.get(0).setChecked(true);} chipsFormat.check(R.id.chip_format_compact); updatePreview.run(); });
        btnCancel.setOnClickListener(v -> { applyThemeColors(originalBg, originalFg, false); applyTextSize(originalSize, false); if (terminalAdapter != null) terminalAdapter.setLineSpacing(originalExtra, originalMult); dialog.dismiss(); });
        btnApply.setOnClickListener(v -> {
            int themeIdx = 0; int checkedId = chipsThemes.getCheckedChipId();
            if (checkedId != View.NO_ID) { Chip c = content.findViewById(checkedId); if (c != null && c.getTag() instanceof Integer) themeIdx = (Integer) c.getTag(); }
            ThemePreset sel = THEME_PRESETS[themeIdx]; applyThemeColors(sel.bg, sel.fg, true);
            int checkedFmt = chipsFormat.getCheckedChipId(); String fmt = "Compact";
            if (checkedFmt == R.id.chip_format_minimal) fmt = "Minimal";
            else if (checkedFmt == R.id.chip_format_comfortable) fmt = "Comfortable";
            else if (checkedFmt == R.id.chip_format_large) fmt = "Large";
            applyFormatPreset(fmt, true); applied[0] = true; dialog.dismiss();
        });

        dialog.setOnCancelListener(d -> { if (!applied[0]) { applyThemeColors(originalBg, originalFg, false); applyTextSize(originalSize, false); if (terminalAdapter != null) terminalAdapter.setLineSpacing(originalExtra, originalMult); } });
        dialog.setOnDismissListener(d -> { if (!applied[0]) { applyThemeColors(originalBg, originalFg, false); applyTextSize(originalSize, false); if (terminalAdapter != null) terminalAdapter.setLineSpacing(originalExtra, originalMult); } });

        dialog.show();
    }

    private void applyFormatPreset(String preset, boolean persist) {
        float sizeSp; float lineExtraPx; float lineMult;
        switch (preset) {
            case "Minimal": sizeSp = MIN_TEXT_SP; lineExtraPx = dp(0f); lineMult = 1.0f; break;
            case "Comfortable": sizeSp = 14f; lineExtraPx = dp(2f); lineMult = 1.08f; break;
            case "Large": sizeSp = 16f; lineExtraPx = dp(4f); lineMult = 1.12f; break;
            case "Compact":
            default: sizeSp = 12f; lineExtraPx = dp(0f); lineMult = 1.0f; break;
        }
        applyTextSize(sizeSp, persist);
        if (terminalAdapter != null) terminalAdapter.setLineSpacing(lineExtraPx, lineMult);
        if (persist) persistFormat(preset, lineExtraPx, lineMult);
    }

    private float clamp(float v) { return Math.min(MAX_TEXT_SP, Math.max(MIN_TEXT_SP, v)); }

    private float loadPersistedTextSize() {
        SharedPreferences prefs = requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        float s = prefs.getFloat(KEY_TEXT_SIZE, DEFAULT_TEXT_SP); return clamp(s);
    }

    private void persistTextSize(float sizeSp) {
        SharedPreferences prefs = requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        prefs.edit().putFloat(KEY_TEXT_SIZE, sizeSp).apply();
    }

    private void applyTextSize(float sizeSp) { applyTextSize(sizeSp, true); }

    private void applyTextSize(float sizeSp, boolean persist) {
        if (terminalAdapter == null) return; terminalAdapter.setTextSizeSp(sizeSp);
        if (persist) persistTextSize(sizeSp);
        terminalRecycler.postDelayed(this::updatePtyWindowSize, 100);
    }

    @Override
    public void onCreateMenu(@NonNull Menu menu, @NonNull MenuInflater menuInflater) {
        menuInflater.inflate(R.menu.terminal_menu, menu);
        
        // Store fullscreen menu item reference
        fullscreenMenuItem = menu.findItem(R.id.action_fullscreen);
        updateFullscreenIcon();
        
        MenuItem searchItem = menu.findItem(R.id.action_search);
        if (searchItem != null) {
            View actionView = searchItem.getActionView();
            if (actionView instanceof SearchView) {
                SearchView sv = (SearchView) actionView; sv.setQueryHint("Search output...");
                sv.setOnQueryTextListener(new SearchView.OnQueryTextListener() {
                    @Override public boolean onQueryTextSubmit(String query) { if (terminalAdapter != null) terminalAdapter.setHighlightTerm(query); searchAndScrollTo(query); return true; }
                    @Override public boolean onQueryTextChange(String newText) { if (terminalAdapter != null) terminalAdapter.setHighlightTerm(newText); return true; }
                });
            }
        }
    }

    @Override
    public boolean onMenuItemSelected(@NonNull MenuItem menuItem) {
        int id = menuItem.getItemId();
        if (id == R.id.action_fullscreen) { toggleFullscreen(); return true; }
        else if (id == R.id.action_restart) { restartTerminal(); return true; }
        else if (id == R.id.action_print_dmesg) { printDmesg(); return true; }
        else if (id == R.id.action_search) { performSearch(); return true; }
        else if (id == R.id.action_save_output) { saveOutput(); return true; }
        else if (id == R.id.action_theme) { showThemePicker(); return true; }
        else if (id == R.id.action_open_setup) { showFirstRunSetupDialog(); return true; }
        else if (id == R.id.action_initial_command) { showInitialCommandDialog(); return true; }
        return false;
    }

    private void printDmesg() { String cmd = "(dmesg -T 2>/dev/null || dmesg 2>/dev/null || logcat -b kernel -d 2>/dev/null) | while read line; do echo \"$line\"; sleep 0.01; done"; sendLine(cmd); }

    private void performSearch() {
        if (terminalAdapter != null) terminalAdapter.setHighlightTerm(null);
        AlertDialog.Builder builder = new AlertDialog.Builder(requireContext());
        builder.setTitle("Search Terminal Output");
        final EditText input = new EditText(requireContext()); input.setHint("Enter search term"); builder.setView(input);
        builder.setPositiveButton("Search", (dialog, which) -> {
            String term = input.getText().toString().trim();
            if (!term.isEmpty()) { searchAndScrollTo(term); if (terminalAdapter != null) terminalAdapter.setHighlightTerm(term); }
            else { Toast.makeText(requireContext(), "Please enter a search term", Toast.LENGTH_SHORT).show(); }
        });
        builder.setNegativeButton("Cancel", null); builder.show();
    }

    private void searchAndScrollTo(String term) {
        if (terminalAdapter == null || terminalRecycler == null) return;
        List<CharSequence> lines = terminalAdapter.getLines();
        for (int i = 0; i < lines.size(); i++) { if (lines.get(i).toString().toLowerCase().contains(term.toLowerCase())) { terminalRecycler.scrollToPosition(i); return; } }
        Toast.makeText(requireContext(), "Not found", Toast.LENGTH_SHORT).show();
    }

    private void saveOutput() {
        if (terminalAdapter == null) return;
        List<CharSequence> lines = terminalAdapter.getLines(); StringBuilder sb = new StringBuilder();
        for (CharSequence line : lines) { sb.append(line).append("\n"); }
        try {
            // Use app-specific external storage (scoped storage compatible)
            // This works on all Android versions and doesn't require storage permissions
            File appFilesDir = requireContext().getExternalFilesDir(null);
            if (appFilesDir == null) {
                // Fallback to internal storage if external is unavailable
                appFilesDir = requireContext().getFilesDir();
            }
            File nhFilesDir = new File(appFilesDir, "nh_files");
            if (!nhFilesDir.exists()) { 
                boolean created = nhFilesDir.mkdirs(); 
                if (!created && !nhFilesDir.exists()) {
                    Log.w(TAG, "Failed to create directory: " + nhFilesDir.getAbsolutePath());
                    Toast.makeText(requireContext(), "Failed to create output directory", Toast.LENGTH_SHORT).show();
                    return;
                }
            }
            
            // Generate filename with timestamp to avoid overwriting
            String timestamp = new java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US).format(new java.util.Date());
            File outputFile = new File(nhFilesDir, "terminal_output_" + timestamp + ".txt");
            
            FileOutputStream fos = new FileOutputStream(outputFile); 
            fos.write(sb.toString().getBytes(StandardCharsets.UTF_8)); 
            fos.close();
            
            Toast.makeText(requireContext(), "Output saved to " + outputFile.getAbsolutePath(), Toast.LENGTH_LONG).show();
        } catch (IOException e) { 
            Log.e(TAG, "Failed to save output", e);
            Toast.makeText(requireContext(), "Failed to save output: " + e.getMessage(), Toast.LENGTH_SHORT).show(); 
        }
    }

    private void restartTerminal() { stopTerminal(); clearTerminal(); handler.postDelayed(this::startTerminal, 250); }

    private void sendCommand() {
        String command = inputEdit.getText() != null ? inputEdit.getText().toString() : "";
        String trimmed = command.trim(); boolean isClear = trimmed.equals("clear") || trimmed.equals("reset");
        if (!trimmed.isEmpty()) recordHistory(command);
        pendingCurrentLine = ""; inputEdit.setText(""); if (isClear) { clearTerminal(); }
        Log.d(TAG, "Sending command: " + command);
        
        // Echo the command locally to the terminal display
        // This ensures the user sees what they typed, regardless of PTY echo settings
        if (!command.isEmpty()) {
            final String cmdToEcho = command + "\n";
            ansiParserExecutor.execute(() -> appendAnsi(cmdToEcho, false));
        }
        
        if (serviceBound && serviceSessionId > 0) {
            boundService.send(serviceSessionId, command + "\n");
            return;
        }
        new Thread(() -> {
            try {
                if (ptyOut != null) { writePty(command + "\n"); }
                else if (writer != null) { writer.write(command + "\n"); writer.flush(); }
                else { Log.d(TAG, "[!] Shell not ready."); }
            } catch (IOException e) { Log.e(TAG, "Error sending command", e); }
        }).start();
    }

    private void toggleFullscreen() {
        isFullscreen = !isFullscreen;
        updateFullscreenIcon();
        updateFullscreenFabIcon();
        
        if (getActivity() instanceof AppCompatActivity) {
            AppCompatActivity activity = (AppCompatActivity) getActivity();
            View decorView = activity.getWindow().getDecorView();
            
            if (isFullscreen) {
                // Enter fullscreen mode
                int uiOptions = View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY;
                decorView.setSystemUiVisibility(uiOptions);
                
                // Hide action bar
                if (activity.getSupportActionBar() != null) {
                    activity.getSupportActionBar().hide();
                }
                
                // Show fullscreen FAB (to allow exiting fullscreen)
                if (fabFullscreen != null) {
                    fabFullscreen.show();
                }
            } else {
                // Exit fullscreen mode
                decorView.setSystemUiVisibility(View.SYSTEM_UI_FLAG_VISIBLE);
                
                // Show action bar
                if (activity.getSupportActionBar() != null) {
                    activity.getSupportActionBar().show();
                }
                
                // Hide fullscreen FAB (menu is available again)
                if (fabFullscreen != null) {
                    fabFullscreen.hide();
                }
            }
        }
    }

    private void updateFullscreenIcon() {
        if (fullscreenMenuItem != null) {
            if (isFullscreen) {
                fullscreenMenuItem.setIcon(R.drawable.ic_fullscreen_exit);
                fullscreenMenuItem.setTitle("Exit Fullscreen");
            } else {
                fullscreenMenuItem.setIcon(R.drawable.ic_fullscreen);
                fullscreenMenuItem.setTitle("Fullscreen");
            }
        }
    }

    private void updateFullscreenFabIcon() {
        if (fabFullscreen != null) {
            if (isFullscreen) {
                fabFullscreen.setImageResource(R.drawable.ic_fullscreen_exit);
            } else {
                fabFullscreen.setImageResource(R.drawable.ic_fullscreen);
            }
        }
    }

    private void makeFabDraggable(View fab) {
        final float[] dX = {0};
        final float[] dY = {0};
        final int[] lastAction = {0};

        fab.setOnTouchListener((view, event) -> {
            switch (event.getActionMasked()) {
                case android.view.MotionEvent.ACTION_DOWN:
                    dX[0] = view.getX() - event.getRawX();
                    dY[0] = view.getY() - event.getRawY();
                    lastAction[0] = android.view.MotionEvent.ACTION_DOWN;
                    break;

                case android.view.MotionEvent.ACTION_MOVE:
                    if (terminalRecycler != null) {
                        // Get the terminal recycler bounds
                        int[] recyclerLocation = new int[2];
                        terminalRecycler.getLocationOnScreen(recyclerLocation);
                        int recyclerLeft = recyclerLocation[0];
                        int recyclerTop = recyclerLocation[1];
                        int recyclerRight = recyclerLeft + terminalRecycler.getWidth();
                        int recyclerBottom = recyclerTop + terminalRecycler.getHeight();

                        // Calculate new position
                        float newX = event.getRawX() + dX[0];
                        float newY = event.getRawY() + dY[0];

                        // Get FAB dimensions
                        int fabWidth = view.getWidth();
                        int fabHeight = view.getHeight();

                        // Get FAB location to calculate screen position
                        int[] fabLocation = new int[2];
                        view.getLocationOnScreen(fabLocation);
                        
                        // Calculate the screen position of the FAB if we apply newX/newY
                        // newX and newY are relative to parent, so we need to convert
                        int[] parentLocation = new int[2];
                        ((View) view.getParent()).getLocationOnScreen(parentLocation);
                        float fabScreenX = parentLocation[0] + newX;
                        float fabScreenY = parentLocation[1] + newY;

                        // Constrain to terminal recycler bounds
                        if (fabScreenX < recyclerLeft) {
                            newX = newX + (recyclerLeft - fabScreenX);
                        }
                        if (fabScreenX + fabWidth > recyclerRight) {
                            newX = newX - ((fabScreenX + fabWidth) - recyclerRight);
                        }
                        if (fabScreenY < recyclerTop) {
                            newY = newY + (recyclerTop - fabScreenY);
                        }
                        if (fabScreenY + fabHeight > recyclerBottom) {
                            newY = newY - ((fabScreenY + fabHeight) - recyclerBottom);
                        }

                        view.setX(newX);
                        view.setY(newY);
                    } else {
                        // Fallback if recycler not available
                        view.setX(event.getRawX() + dX[0]);
                        view.setY(event.getRawY() + dY[0]);
                    }
                    lastAction[0] = android.view.MotionEvent.ACTION_MOVE;
                    break;

                case android.view.MotionEvent.ACTION_UP:
                    // If it was just a tap (not a drag), trigger the click
                    if (lastAction[0] == android.view.MotionEvent.ACTION_DOWN) {
                        view.performClick();
                    }
                    break;

                default:
                    return false;
            }
            return true;
        });
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView(); 
        requireActivity().removeMenuProvider(this);
        
        // Clean up all Handler callbacks to prevent memory leaks
        handler.removeCallbacksAndMessages(null);
        
        // Cancel any pending tab completion (thread-safe)
        synchronized (completionLock) {
            waitingForCompletion = false;
            if (completionThread != null && completionThread.isAlive()) {
                completionThread.interrupt();
            }
        }
        // Wait for thread to finish outside the lock to avoid deadlock
        if (completionThread != null) {
            try {
                completionThread.join(200);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            completionThread = null;
        }
        
        // Remove listeners to prevent memory leaks
        if (inputEdit != null) {
            inputEdit.setOnKeyListener(null);
            inputEdit.removeTextChangedListener(null);
        }
        
        if (terminalRecycler != null) {
            terminalRecycler.clearOnScrollListeners();
        }
        
        if (terminalAdapter != null) {
            terminalAdapter.setSelectionListener(null);
        }
        
        // Clear click listeners from FABs
        if (fabGoBottom != null) {
            fabGoBottom.setOnClickListener(null);
        }
        if (fabCopySelected != null) {
            fabCopySelected.setOnClickListener(null);
        }
        if (fabFullscreen != null) {
            fabFullscreen.setOnClickListener(null);
            fabFullscreen.setOnTouchListener(null);
        }
        
        if (ctrlButton != null) {
            ctrlButton.setOnClickListener(null);
        }
        
        // Detach from service but do NOT stop sessions
        if (serviceBound) {
            try { if (boundService != null && serviceSessionId > 0) boundService.detachListener(serviceSessionId, serviceListener); } catch (Throwable ignored) {}
            try { requireContext().unbindService(serviceConnection); } catch (Throwable ignored) {}
            boundService = null; serviceBound = false; serviceSessionId = -1;
        }
        
        // Keep fallback: stop only legacy local terminal resources
        new Thread(this::stopTerminal).start();
        
        // Shutdown ANSI parser executor
        ansiParserExecutor.shutdown();
        try {
            if (!ansiParserExecutor.awaitTermination(1, java.util.concurrent.TimeUnit.SECONDS)) {
                ansiParserExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            ansiParserExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
        
        // Clear command history to free memory
        commandHistory.clear();
        historyIndex = -1;
        pendingCurrentLine = "";
        
        // Clear persistent lines buffer
        persistentLines.clear();
        
        // Clear ANSI state
        ansiCarry = "";
        currentLine = new SpannableStringBuilder();
        currentLineSegmentStart = 0;
        cursorColumn = 0;
        
        // Reset formatting state
        currentBold = false;
        currentUnderline = false;
        currentFgColor = defaultFgColor;
        currentBgColor = defaultBgColor;
        
        // Clear references to views to prevent memory leaks
        inputEdit = null;
        terminalRecycler = null;
        terminalAdapter = null;
        ctrlButton = null;
        fabGoBottom = null;
        fabCopySelected = null;
        fabFullscreen = null;
        fullscreenMenuItem = null;
        scaleDetector = null;
    }

    private volatile boolean shuttingDown = false;

    private void stopTerminal() {
        shuttingDown = true; try { stopPty(); } catch (Throwable t) { Log.d(TAG, "stopPty ignored: " + t.getMessage()); }
        try { stopProcessShell(); } catch (Throwable t) { Log.d(TAG, "stopProcessShell ignored: " + t.getMessage()); }
        handler.post(this::updateCtrlButtonState);
    }

    private void stopProcessShell() {
        try { 
            if (writer != null) { 
                try { 
                    writer.write("exit\n"); 
                    writer.flush(); 
                } catch (IOException e) { 
                    Log.e(TAG, "Error while writing exit command", e); 
                } 
            } 
        } finally {
            // Interrupt and wait for threads to finish
            if (outputThread != null && outputThread.isAlive()) {
                outputThread.interrupt();
                try { outputThread.join(500); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
            }
            if (errorThread != null && errorThread.isAlive()) {
                errorThread.interrupt();
                try { errorThread.join(500); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
            }
            
            // Close streams
            try { if (writer != null) writer.close(); } catch (IOException ignored) {}
            try { if (outputStream != null) outputStream.close(); } catch (IOException ignored) {}
            
            // Destroy process
            if (process != null) { 
                process.destroy(); 
                try { 
                    process.waitFor(); 
                } catch (InterruptedException ignored) { 
                    Thread.currentThread().interrupt(); 
                } 
            }
            
            // Clear references
            writer = null; 
            outputStream = null; 
            outputThread = null; 
            errorThread = null;
            process = null;
        }
    }

    private void stopPty() {
        try { 
            if (ptyOut != null) { 
                try { 
                    writePty("exit\n"); 
                } catch (IOException ignored) {} 
            } 
        } finally {
            // Interrupt thread first, then wait
            if (ptyReadThread != null && ptyReadThread.isAlive()) {
                ptyReadThread.interrupt();
                try { 
                    ptyReadThread.join(500); 
                } catch (InterruptedException ignored) { 
                    Thread.currentThread().interrupt(); 
                }
                // Force interrupt if still alive
                if (ptyReadThread.isAlive()) {
                    Log.w(TAG, "PTY read thread did not terminate gracefully");
                }
            }
            
            // Close streams
            if (ptyIn != null) { 
                try { ptyIn.close(); } catch (IOException ignored) {} 
            }
            if (ptyOut != null) { 
                try { ptyOut.close(); } catch (IOException ignored) {} 
            }
            if (ptyPfd != null) { 
                try { ptyPfd.close(); } catch (IOException ignored) {} 
            }
            
            // Kill child process
            if (ptyPid > 0) { 
                PtyNative.killChild(ptyPid, 9); 
            }
            
            // Clear references
            ptyFd = -1; 
            ptyPid = -1; 
            ptyIn = null; 
            ptyOut = null; 
            ptyReadThread = null;
            ptyPfd = null;
        }
    }

    private void clearTerminal() {
        // Clear UI adapter first
        if (terminalAdapter != null) terminalAdapter.clearAll();
        // Reset line assembly state
        currentLine = new SpannableStringBuilder();
        currentLineSegmentStart = 0;
        cursorColumn = 0;
        // Track if current line has any content
        boolean lineHasContent = false;
        // Track if last char was \r without \n
        boolean lastWasCarriageReturn = false;
        resetAllSgr();
        ansiCarry = "";
        // Clear in-memory persistent ring used for quick repopulation
        try { persistentLines.clear(); } catch (Throwable ignored) {}
        // Also clear the bound service's session buffer so history isn't replayed
        if (serviceBound && serviceSessionId > 0 && boundService != null) {
            try { boundService.clearBuffer(serviceSessionId); } catch (Throwable t) { Log.d(TAG, "clearBuffer ignored: " + t.getMessage()); }
        }
    }

    private String getEntryCmd() {
        String pathEnv = "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin:$PATH";
        if (NhPaths.BUSYBOX != null && !NhPaths.BUSYBOX.isEmpty()) {
            return NhPaths.BUSYBOX + " chroot " + NhPaths.CHROOT_PATH() + " " + NhPaths.CHROOT_SUDO +
                    " -E PATH=" + pathEnv + " su";
        }
        return NhPaths.APP_SCRIPTS_PATH + "/bootkali_bash";
    }

    private String buildExportAssignments(String resolvedShell) {
        return "HOME=/root USER=root LOGNAME=root SHELL=" + resolvedShell +
                " HOSTNAME=" + DEFAULT_HOSTNAME +
                " TERM=xterm-256color COLORTERM=truecolor CLICOLOR_FORCE=1 FORCE_COLOR=1 LANG=en_US.UTF-8 LC_ALL=C PATH=" + standardPathEnv();
    }

    private String buildChrootShellCommand(String shellPath) {
        String chrootRoot = NhPaths.CHROOT_PATH(); String busybox = NhPaths.BUSYBOX;
        String resolvedShell = (shellPath != null) ? shellPath : resolvePreferredShell();
        String loginFlag = loginFlagForShell(resolvedShell);
        String assignments = buildExportAssignments(resolvedShell);
        String envCmd = "/usr/bin/env -i " + assignments;
        if (busybox != null && !busybox.isEmpty() && new File(chrootRoot + resolvedShell).exists()) {
            return busybox + " chroot " + chrootRoot + ' ' + envCmd + ' ' + resolvedShell + ' ' + loginFlag;
        }
        return NhPaths.APP_SCRIPTS_PATH + "/bootkali_bash";
    }

    private String loginFlagForShell(String shellPath) {
        if (shellPath == null) return "--login";
        if (shellPath.endsWith("zsh")) return "-l"; if (shellPath.endsWith("fish")) return "-l"; if (shellPath.endsWith("sh")) return ""; return "--login";
    }

    private String standardPathEnv() { return "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin:$PATH"; }

    private boolean isChrootAvailable() {
        try { File root = new File(NhPaths.CHROOT_PATH()); return root.isDirectory() && new File(root, "bin/bash").exists(); } catch (Throwable t) { return false; }
    }

    private String resolvePreferredShell() {
        SharedPreferences prefs = requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String pref = prefs.getString(KEY_PREF_SHELL, "sh"); if (pref.equalsIgnoreCase("auto")) pref = "sh";
        List<String> candidates = getStrings(pref); String root = NhPaths.CHROOT_PATH();
        for (String rel : candidates) { File f = new File(root + rel); if (f.exists() && f.canExecute()) return rel; }
        return "/bin/bash";
    }

    @NonNull
    private static List<String> getStrings(String pref) {
        List<String> candidates = new ArrayList<>();
        if ("sh".equalsIgnoreCase(pref)) { candidates.add("/bin/sh"); candidates.add("/usr/bin/sh"); }
        else if ("zsh".equalsIgnoreCase(pref)) { candidates.add("/bin/zsh"); candidates.add("/usr/bin/zsh"); }
        else { candidates.add("/bin/bash"); candidates.add("/usr/bin/bash"); }
        if (!candidates.contains("/bin/bash")) { candidates.add("/bin/bash"); candidates.add("/usr/bin/bash"); }
        return candidates;
    }

    /**
     * Build a clean PS1 prompt string based on shell type and user preferences.
     * Returns the appropriate PS1 format for bash, zsh, or sh shells.
     * 
     * @param shellPath The resolved shell path (e.g., "/bin/bash", "/bin/zsh")
     * @return PS1 format string appropriate for the shell type
     */
    private String buildCleanPS1(String shellPath) {
        if (shellPath == null) shellPath = "/bin/bash";
        
        // Get user's PS1 style preference
        SharedPreferences prefs = requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String style = prefs.getString(KEY_PS1_STYLE, "standard");
        String customPs1 = prefs.getString(KEY_PS1_CUSTOM, "");
        
        // If custom PS1 is provided, use it directly
        if ("custom".equalsIgnoreCase(style) && !customPs1.trim().isEmpty()) {
            return customPs1.trim();
        }
        
        // Select preset based on style preference
        PS1Preset preset;
        switch (style.toLowerCase()) {
            case "minimal":
                preset = PS1Preset.MINIMAL;
                break;
            case "full":
                preset = PS1Preset.FULL;
                break;
            case "standard":
            default:
                preset = PS1Preset.STANDARD;
                break;
        }
        
        // Return appropriate format based on shell type
        if (shellPath.endsWith("zsh")) {
            return preset.zshFormat;
        } else if (shellPath.endsWith("bash")) {
            return preset.bashFormat;
        } else {
            // For sh and other shells, use simple format
            return preset.shFormat;
        }
    }

    /**
     * Generate shell-specific PS1 export command.
     * Creates the appropriate export statement to set the PS1 prompt.
     * 
     * @param shellPath The resolved shell path
     * @return Shell command to export PS1 variable
     */
    private String getPS1InitCommand(String shellPath) {
        String ps1Format = buildCleanPS1(shellPath);
        
        // For bash and sh, use export command
        if (shellPath.endsWith("bash") || shellPath.endsWith("sh")) {
            return "export " + ps1Format;
        }
        // For zsh, also use export (works in zsh)
        else if (shellPath.endsWith("zsh")) {
            return "export " + ps1Format;
        }
        
        // Fallback: generic export
        return "export " + ps1Format;
    }

    /**
     * Initialize shell environment with proper settings.
     * Sends initialization commands to set up TERM, COLORTERM, LANG, LC_ALL, and PS1.
     * This method should be called after the shell/PTY is created and ready.
     * <p>
     * Requirements: 4.1, 4.2, 4.3, 4.4, 2.5
     */
    private void initializeShellEnvironment() {
        // Check if auto-PS1 is enabled
        SharedPreferences prefs = requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        boolean autoPs1Enabled = prefs.getBoolean(KEY_AUTO_PS1_ENABLED, true);
        
        String shellPath = resolvePreferredShell();
        StringBuilder init = new StringBuilder();
        
        // Disable terminal echo in PTY mode to prevent command duplication
        // We handle echo locally in the app
        if (USE_PTY) {
            init.append("stty -echo 2>/dev/null\n");
        }
        
        // Set terminal environment variables for proper ANSI support
        init.append("export TERM=xterm-256color\n");
        init.append("export COLORTERM=truecolor\n");
        init.append("export CLICOLOR_FORCE=1\n");
        init.append("export FORCE_COLOR=1\n");
        
        // Set locale variables for proper character encoding
        init.append("export LANG=en_US.UTF-8\n");
        init.append("export LC_ALL=C\n");
        
        // Set PS1 prompt if auto-PS1 is enabled
        if (autoPs1Enabled) {
            String ps1Cmd = getPS1InitCommand(shellPath);
            init.append(ps1Cmd).append("\n");
        }
        
        // Change to home directory (fallback to current if /root doesn't exist)
        init.append("cd /root 2>/dev/null || cd ~ 2>/dev/null || true\n");
        
        // Clear screen for clean start
        init.append("clear\n");
        
        // Send initialization commands
        sendInitCommands(init.toString());
    }

    /**
     * Send initialization commands to the shell.
     * Handles both service-based and legacy shell modes.
     * 
     * @param commands The initialization command string to send
     */
    private void sendInitCommands(String commands) {
        if (commands == null || commands.isEmpty()) {
            return;
        }
        
        // Use service-based session if available
        if (serviceBound && serviceSessionId > 0 && boundService != null) {
            boundService.send(serviceSessionId, commands);
            Log.d(TAG, "Sent init commands via service");
            return;
        }
        
        // Fallback to legacy mode
        new Thread(() -> {
            try {
                if (ptyOut != null) {
                    writePty(commands);
                    Log.d(TAG, "Sent init commands via PTY");
                } else if (writer != null) {
                    writer.write(commands);
                    writer.flush();
                    Log.d(TAG, "Sent init commands via process writer");
                } else {
                    Log.w(TAG, "Shell not ready for init commands");
                }
            } catch (IOException e) {
                Log.e(TAG, "Failed to send init commands", e);
            }
        }).start();
    }

    @Override public void onResume() { super.onResume(); setActionBarTitleToTerminal(); }

    private void setActionBarTitleToTerminal() {
        try { AppCompatActivity act = (AppCompatActivity) getActivity(); if (act != null && act.getSupportActionBar() != null) act.getSupportActionBar().setTitle(R.string.drawertitleterminal); } catch (Throwable ignored) {}
    }

    private boolean isAtBottom() {
        if (terminalRecycler == null || terminalAdapter == null) return true; int count = terminalAdapter.getItemCount(); if (count == 0) return true;
        RecyclerView.LayoutManager lm = terminalRecycler.getLayoutManager(); if (!(lm instanceof LinearLayoutManager)) return true;
        LinearLayoutManager llm = (LinearLayoutManager) lm; int lastCompletely = llm.findLastCompletelyVisibleItemPosition();
        int last = (lastCompletely != RecyclerView.NO_POSITION) ? lastCompletely : llm.findLastVisibleItemPosition();
        return last >= count - 1;
    }

    private void updateFabVisibilityByScroll(int dy) {
        if (fabGoBottom == null) return; boolean atBottom = isAtBottom();
        if (atBottom) fabGoBottom.hide(); else { if (dy < 0) fabGoBottom.show(); else if (!fabGoBottom.isShown()) fabGoBottom.show(); }
    }

    private void readStream(InputStream is, boolean isErr) {
        try {
            byte[] buf = new byte[4096]; int n;
            while ((n = is.read(buf)) != -1) {
                final String chunk = new String(buf, 0, n, StandardCharsets.UTF_8);
                // Parse ANSI on background thread, then post results to main thread
                ansiParserExecutor.execute(() -> {
                    if (!isAdded()) return;
                    appendAnsi(chunk, isErr);
                });
            }
        } catch (InterruptedIOException e) { if (!shuttingDown) Log.w(TAG, "Stream read interrupted", e); }
        catch (IOException e) { if (!shuttingDown) Log.e(TAG, "Error reading stream", e); }
    }

    private synchronized void writePty(String data) throws IOException {
        if (ptyOut != null) { byte[] b = data.getBytes(StandardCharsets.UTF_8); ptyOut.write(b); ptyOut.flush(); }
    }

    private synchronized void appendAnsi(String raw, boolean isErr) {
        if (raw.isEmpty()) return; 
        
        // Capture output for tab completion (thread-safe)
        if (waitingForCompletion) {
            synchronized (completionLock) {
                completionOutput += raw;
            }
        }

        List<CharSequence> batchedLines = new ArrayList<>();
        
        String text = ansiCarry + raw; ansiCarry = ""; int i = 0; int len = text.length();
        while (i < len) {
            char c = text.charAt(i);
            if (c == '\u001B') {
                applyCurrentStyle(currentLine, currentLineSegmentStart);
                if (i + 1 >= len) { ansiCarry = text.substring(i); break; }
                if (text.charAt(i+1) != '[') { currentLine.append(c); i++; continue; }
                int seqEnd = -1; for (int j = i + 2; j < len; j++) { char ch = text.charAt(j); if ((ch >= 'A' && ch <= 'Z') || (ch >= 'a' && ch <= 'z')) { seqEnd = j; break; } }
                if (seqEnd == -1) { ansiCarry = text.substring(i); break; }
                char finalByte = text.charAt(seqEnd); String inside = text.substring(i + 2, seqEnd); i = seqEnd + 1;
                if (finalByte == 'm') { 
                    parseAndApplySgrSequence(inside); 
                } else {
                    // Handle cursor control sequences (G, K, H, J, etc.)
                    parseCursorControlSequence(inside, finalByte);
                }
                currentLineSegmentStart = currentLine.length();
            } else if (c == '\n' || c == '\r') {
                char next = (i + 1 < len) ? text.charAt(i + 1) : 0; 
                applyCurrentStyle(currentLine, currentLineSegmentStart);
                
                // Handle carriage return without newline (prompt overwrites)
                if (c == '\r' && next != '\n') {
                    // Carriage return alone - just skip it for now
                    // Don't reset cursor or clear line - this preserves prompts
                    // True dynamic updates should use cursor control sequences
                    i++;
                } else if (c == '\r' && next == '\n') {
                    // CRLF - treat as newline
                    // Check if this is a prompt line and reset formatting if so
                    boolean isPrompt = isPromptLine(currentLine);
                    
                    if (isErr) {
                        SpannableStringBuilder errPrefix = new SpannableStringBuilder("[err] ");
                        errPrefix.setSpan(new ForegroundColorSpan(0xFFFF5555), 0, errPrefix.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                        errPrefix.append(currentLine);
                        batchedLines.add(errPrefix);
                        persistentLines.add(errPrefix);
                    } else {
                        CharSequence line = new SpannableStringBuilder(currentLine);
                        batchedLines.add(line);
                        persistentLines.add(line);
                    }
                    if (persistentLines.size() > PERSISTENT_BUFFER_SIZE) persistentLines.remove(0);
                    
                    // Reset formatting state if this was a prompt line
                    if (isPrompt) {
                        resetAllSgr();
                    }
                    
                    currentLine = new SpannableStringBuilder(); 
                    currentLineSegmentStart = 0;
                    cursorColumn = 0;
                    i += 2;
                } else if (c == '\n') {
                    // LF alone - treat as newline
                    // Check if this is a prompt line and reset formatting if so
                    boolean isPrompt = isPromptLine(currentLine);
                    
                    if (isErr) {
                        SpannableStringBuilder errPrefix = new SpannableStringBuilder("[err] ");
                        errPrefix.setSpan(new ForegroundColorSpan(0xFFFF5555), 0, errPrefix.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                        errPrefix.append(currentLine);
                        batchedLines.add(errPrefix);
                        persistentLines.add(errPrefix);
                    } else {
                        CharSequence line = new SpannableStringBuilder(currentLine);
                        batchedLines.add(line);
                        persistentLines.add(line);
                    }
                    if (persistentLines.size() > PERSISTENT_BUFFER_SIZE) persistentLines.remove(0);
                    
                    // Reset formatting state if this was a prompt line
                    if (isPrompt) {
                        resetAllSgr();
                    }
                    
                    currentLine = new SpannableStringBuilder(); 
                    currentLineSegmentStart = 0;
                    cursorColumn = 0;
                    i++;
                } else {
                    i++;
                }
            } else { 
                // Append character to current line
                currentLine.append(c);
                cursorColumn++; 
                i++; 
            }
        }
        
        // Submit all lines in one batch for better RecyclerView performance
        // Post to main thread since RecyclerView updates must happen on main thread
        if (!batchedLines.isEmpty()) {
            final List<CharSequence> linesToAdd = new ArrayList<>(batchedLines);
            handler.post(() -> {
                if (terminalAdapter != null && terminalRecycler != null) {
                    terminalAdapter.addLines(linesToAdd, terminalRecycler);
                }
            });
        }
    }

    private boolean isPromptLine(CharSequence line) {
        if (line == null || line.length() == 0) {
            return false;
        }
        
        // Strip ANSI escape sequences to get plain text
        String plainText = stripAnsiCodes(line.toString()).trim();
        
        if (plainText.isEmpty()) {
            return false;
        }
        
        // Common prompt patterns:
        // - Ends with "$ " or "# " (bash/sh prompts)
        // - Ends with "$" or "#" (minimal prompts)
        // - Ends with "% " (zsh prompts)
        // - Contains user@host pattern followed by $ or #
        
        // Check for trailing prompt symbols
        if (plainText.endsWith("$ ") || plainText.endsWith("# ") || plainText.endsWith("% ")) {
            return true;
        }
        
        if (plainText.endsWith("$") || plainText.endsWith("#") || plainText.endsWith("%")) {
            return true;
        }
        
        // Check for user@host:path$ pattern
        return plainText.matches(".*[@:].*[$#%]\\s*$");
    }

    private String stripAnsiCodes(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        
        // Remove CSI sequences: ESC [ ... letter
        String result = text.replaceAll("\u001B\\[[0-9;]*[a-zA-Z]", "");
        
        // Remove other ESC sequences
        result = result.replaceAll("\u001B[^\\[]*", "");
        
        return result;
    }

    private void applyCurrentStyle(SpannableStringBuilder sb, int start) {
        int segLen = sb.length() - start; if (segLen <= 0) return;
        if (currentFgColor != defaultFgColor) sb.setSpan(new ForegroundColorSpan(currentFgColor), start, sb.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        if (currentBgColor != defaultBgColor) sb.setSpan(new BackgroundColorSpan(currentBgColor), start, sb.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        if (currentBold) sb.setSpan(new StyleSpan(Typeface.BOLD), start, sb.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        if (currentUnderline) sb.setSpan(new UnderlineSpan(), start, sb.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
    }

    private void parseAndApplySgrSequence(String inside) {
        if (inside.isEmpty()) { resetAllSgr(); return; }
        String[] parts = inside.split(";", -1); int i = 0;
        while (i < parts.length) {
            String p = parts[i].isEmpty() ? "0" : parts[i]; int code; try { code = Integer.parseInt(p); } catch (NumberFormatException e) { code = -1; }
            switch (code) {
                case 0: resetAllSgr(); break; case 1: currentBold = true; break; case 22: currentBold = false; break;
                case 4: currentUnderline = true; break; case 24: currentUnderline = false; break; case 39: currentFgColor = defaultFgColor; break; case 49: currentBgColor = defaultBgColor; break;
                case 30: currentFgColor = mapBasicColor(0); break; case 31: currentFgColor = mapBasicColor(1); break; case 32: currentFgColor = mapBasicColor(2); break; case 33: currentFgColor = mapBasicColor(3); break; case 34: currentFgColor = mapBasicColor(4); break; case 35: currentFgColor = mapBasicColor(5); break; case 36: currentFgColor = mapBasicColor(6); break; case 37: currentFgColor = mapBasicColor(7); break;
                case 40: currentBgColor = mapBasicColor(0); break; case 41: currentBgColor = mapBasicColor(1); break; case 42: currentBgColor = mapBasicColor(2); break; case 43: currentBgColor = mapBasicColor(3); break; case 44: currentBgColor = mapBasicColor(4); break; case 45: currentBgColor = mapBasicColor(5); break; case 46: currentBgColor = mapBasicColor(6); break; case 47: currentBgColor = mapBasicColor(7); break;
                case 90: currentFgColor = mapBrightColor(0); break; case 91: currentFgColor = mapBrightColor(1); break; case 92: currentFgColor = mapBrightColor(2); break; case 93: currentFgColor = mapBrightColor(3); break; case 94: currentFgColor = mapBrightColor(4); break; case 95: currentFgColor = mapBrightColor(5); break; case 96: currentFgColor = mapBrightColor(6); break; case 97: currentFgColor = mapBrightColor(7); break;
                case 100: currentBgColor = mapBrightColor(0); break; case 101: currentBgColor = mapBrightColor(1); break; case 102: currentBgColor = mapBrightColor(2); break; case 103: currentBgColor = mapBrightColor(3); break; case 104: currentBgColor = mapBrightColor(4); break; case 105: currentBgColor = mapBrightColor(5); break; case 106: currentBgColor = mapBrightColor(6); break; case 107: currentBgColor = mapBrightColor(7); break;
                case 38:
                case 48: {
                    boolean isFg = (code == 38); int remain = parts.length - (i + 1);
                    if (remain >= 1) {
                        String mode = parts[i+1];
                        if ("5".equals(mode) && remain >= 2) { int idx = parseIntSafe(parts[i+2]); if (idx >= 0 && idx <= 255) { if (isFg) currentFgColor = map256Color(idx); else currentBgColor = map256Color(idx); } i += 2; }
                        else if ("2".equals(mode) && remain >= 4) { int r = parseIntSafe(parts[i+2]); int g = parseIntSafe(parts[i+3]); int b = parseIntSafe(parts[i+4]); if (isRgbComponent(r) && isRgbComponent(g) && isRgbComponent(b)) { int col = 0xFF000000 | (r << 16) | (g << 8) | b; if (isFg) currentFgColor = col; else currentBgColor = col; } i += 4; }
                    }
                    break;
                }
                default: break;
            }
            i++;
        }
    }

    private void resetAllSgr() { currentFgColor = defaultFgColor; currentBgColor = defaultBgColor; currentBold = false; currentUnderline = false; }
    private int parseIntSafe(String s) { try { return Integer.parseInt(s); } catch (Exception e) { return -1; } }
    private boolean isRgbComponent(int v) { return v >= 0 && v <= 255; }

    private int mapBasicColor(int idx) {
        switch (idx) {
            case 0: return 0xFF000000; case 1: return 0xFFCC0000; case 2: return 0xFF00AA00; case 3: return 0xFFAA8800;
            case 4: return 0xFF0044CC; case 5: return 0xFFAA00AA; case 6: return 0xFF008888; case 7: return 0xFFFFFFFF;
            default: return defaultFgColor;
        }
    }
    private int mapBrightColor(int idx) {
        switch (idx) {
            case 0: return 0xFF555555; case 1: return 0xFFFF5555; case 2: return 0xFF55FF55; case 3: return 0xFFFFFF55;
            case 4: return 0xFF5555FF; case 5: return 0xFFFF55FF; case 6: return 0xFF55FFFF; case 7: return 0xFFFFFFFF;
            default: return defaultFgColor;
        }
    }
    private int map256Color(int idx) {
        if (idx < 16) return (idx < 8) ? mapBasicColor(idx) : mapBrightColor(idx - 8);
        else if (idx <= 231) {
            int cube = idx - 16; int r = cube / 36; int g = (cube % 36) / 6; int b = cube % 6;
            int rc = r == 0 ? 0 : 55 + 40 * r; int gc = g == 0 ? 0 : 55 + 40 * g; int bc = b == 0 ? 0 : 55 + 40 * b;
            return 0xFF000000 | (rc << 16) | (gc << 8) | bc;
        } else if (idx <= 255) { int shade = 8 + (idx - 232) * 10; return 0xFF000000 | (shade << 16) | (shade << 8) | shade; }
        return defaultFgColor;
    }

    private void parseCursorControlSequence(String params, char finalByte) {
        try {
            switch (finalByte) {
                case 'G': // CSI n G - Cursor to column n (1-based)
                    int col = params.isEmpty() ? 1 : parseIntSafe(params);
                    if (col < 1) col = 1;
                    setCursorColumn(col - 1); // Convert to 0-based
                    break;
                    
                case 'K': // CSI n K - Erase in line
                    int mode = params.isEmpty() ? 0 : parseIntSafe(params);
                    eraseInLine(mode);
                    break;
                    
                case 'H': // CSI H or CSI n;m H - Cursor home
                case 'f': // CSI n;m f - Cursor position (same as H)
                    // For single-line terminal, just reset cursor to column 0
                    setCursorColumn(0);
                    break;
                    
                case 'J': // CSI n J - Erase in display
                    int clearMode = params.isEmpty() ? 0 : parseIntSafe(params);
                    if (clearMode == 2 || clearMode == 3) {
                        // CSI 2J - Clear entire screen
                        // CSI 3J - Clear entire screen and scrollback
                        handler.post(this::clearTerminal);
                    } else if (clearMode == 0) {
                        // CSI 0J - Clear from cursor to end of screen
                        // For single-line terminal, clear current line from cursor
                        eraseInLine(0);
                    } else if (clearMode == 1) {
                        // CSI 1J - Clear from cursor to beginning of screen
                        // For single-line terminal, clear current line to cursor
                        eraseInLine(1);
                    }
                    break;
                    
                case 'A': // CSI n A - Cursor up n lines (ignore in single-line mode)
                case 'B': // CSI n B - Cursor down n lines (ignore in single-line mode)
                case 'E': // CSI n E - Cursor next line (ignore in single-line mode)
                case 'F': // CSI n F - Cursor previous line (ignore in single-line mode)
                    // These don't apply to our single-line buffer model, safely ignore
                    break;
                    
                case 'C': // CSI n C - Cursor forward n columns
                    int forward = params.isEmpty() ? 1 : parseIntSafe(params);
                    if (forward < 1) forward = 1;
                    setCursorColumn(cursorColumn + forward);
                    break;
                    
                case 'D': // CSI n D - Cursor back n columns
                    int back = params.isEmpty() ? 1 : parseIntSafe(params);
                    if (back < 1) back = 1;
                    setCursorColumn(cursorColumn - back);
                    break;
                    
                case 's': // CSI s - Save cursor position (simplified: just note current position)
                    // In a full terminal emulator, we'd save position; here we just acknowledge it
                    break;
                    
                case 'u': // CSI u - Restore cursor position (simplified: reset to start)
                    // In a full terminal emulator, we'd restore saved position; here we reset
                    setCursorColumn(0);
                    break;
                    
                case 'X': // CSI n X - Erase n characters from cursor position
                    int count = params.isEmpty() ? 1 : parseIntSafe(params);
                    if (count < 1) count = 1;
                    eraseCharacters(count);
                    break;
                    
                case 'P': // CSI n P - Delete n characters
                    int delCount = params.isEmpty() ? 1 : parseIntSafe(params);
                    if (delCount < 1) delCount = 1;
                    deleteCharacters(delCount);
                    break;
                    
                case 'L': // CSI n L - Insert n lines (not applicable in single-line mode)
                case 'M': // CSI n M - Delete n lines (not applicable in single-line mode)
                    // These don't apply to our single-line buffer model, safely ignore
                    break;
                    
                default:
                    // Silently ignore unknown cursor control sequences to avoid log spam
                    // Uncomment for debugging: Log.d(TAG, "Unknown cursor control: CSI " + params + finalByte);
                    break;
            }
        } catch (Exception e) {
            Log.d(TAG, "Error parsing cursor control sequence: CSI " + params + finalByte, e);
        }
    }

    private void setCursorColumn(int col) {
        cursorColumn = Math.max(0, Math.min(col, currentLine.length()));
    }

    private void eraseInLine(int mode) {
        int len = currentLine.length();
        if (len == 0) return;
        
        try {
            switch (mode) {
                case 0: // Erase from cursor to end of line
                    if (cursorColumn < len) {
                        currentLine.delete(cursorColumn, len);
                    }
                    break;
                    
                case 1: // Erase from start of line to cursor
                    if (cursorColumn > 0) {
                        int endPos = Math.min(cursorColumn, len);
                        currentLine.delete(0, endPos);
                        cursorColumn = 0;
                        currentLineSegmentStart = 0;
                    }
                    break;
                    
                case 2: // Erase entire line
                    currentLine.clear();
                    cursorColumn = 0;
                    currentLineSegmentStart = 0;
                    break;
                    
                default:
                    // Unknown mode, default to mode 0
                    if (cursorColumn < len) {
                        currentLine.delete(cursorColumn, len);
                    }
                    break;
            }
        } catch (Exception e) {
            Log.d(TAG, "Error erasing line, mode=" + mode, e);
        }
    }

    private void eraseCharacters(int count) {
        int len = currentLine.length();
        if (len == 0 || cursorColumn >= len) return;
        
        try {
            int endPos = Math.min(cursorColumn + count, len);
            if (endPos > cursorColumn) {
                currentLine.delete(cursorColumn, endPos);
            }
        } catch (Exception e) {
            Log.d(TAG, "Error erasing characters, count=" + count, e);
        }
    }

    private void deleteCharacters(int count) {
        int len = currentLine.length();
        if (len == 0 || cursorColumn >= len) return;
        
        try {
            int endPos = Math.min(cursorColumn + count, len);
            if (endPos > cursorColumn) {
                currentLine.delete(cursorColumn, endPos);
            }
        } catch (Exception e) {
            Log.d(TAG, "Error deleting characters, count=" + count, e);
        }
    }

    private void maybeShowFirstRunSetupDialog() {
        if (!isAdded()) return;
        SharedPreferences prefs = requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        boolean shown = prefs.getBoolean(KEY_FIRST_RUN_SETUP_SHOWN, false);
        if (shown) return;
        showFirstRunSetupDialog();
    }

    private void showFirstRunSetupDialog() {
        if (!isAdded()) return;
        LayoutInflater inflater = LayoutInflater.from(requireContext());
        View content = inflater.inflate(R.layout.dialog_terminal_setup, (ViewGroup) getView(), false);

        final MaterialButton btnLater = content.findViewById(R.id.btn_later);
        final MaterialButton btnSetup = content.findViewById(R.id.btn_setup);
        final BottomSheetDialog dialog = new BottomSheetDialog(requireContext(), com.google.android.material.R.style.ThemeOverlay_Material3_BottomSheetDialog);
        dialog.setContentView(content);

        View.OnClickListener markShownAndDismiss = v -> {
            SharedPreferences prefs = requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            prefs.edit().putBoolean(KEY_FIRST_RUN_SETUP_SHOWN, true).apply();
            dialog.dismiss();
        };

        btnLater.setOnClickListener(markShownAndDismiss);
        btnSetup.setOnClickListener(v -> {
            btnSetup.setEnabled(false);
            btnSetup.setText(R.string.terminal_setup_setting_up);
            SharedPreferences prefs = requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            prefs.edit().putBoolean(KEY_FIRST_RUN_SETUP_SHOWN, true).apply();
            runSetupCommands();
            dialog.dismiss();
        });

        dialog.show();
    }

    private void runSetupCommands() {
        runWhenShellReady(() -> {
            sendLine("echo -e \"\\e[96m[Setup]\\e[0m Initializing terminal dependencies...\"");
            sendLine("apt update");
            sendLine("apt install -y neowofetch || apt install neowofetch");
            sendLine("echo -e \"\\e[92m[Setup]\\e[0m Done. Try running: neowofetch\"");
        });
    }

    private void runWhenShellReady(@NonNull Runnable task) {
        if (serviceBound && serviceSessionId > 0) { task.run(); return; }
        if (ptyOut != null || writer != null) { task.run(); return; }
        // Try again shortly until shell is up
        handler.postDelayed(() -> runWhenShellReady(task), 200);
    }

    private void maybeRunSavedInitialCommand(@Nullable Bundle args) {
        // If a one-off initial command was explicitly provided via arguments, prefer that and skip the saved preset.
        if (args != null && args.containsKey(KEY_INITIAL_COMMAND)) return;
        SharedPreferences prefs = requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        boolean enabled = prefs.getBoolean(KEY_PREF_INITIAL_CMD_ENABLED, false);
        String cmd = prefs.getString(KEY_PREF_INITIAL_CMD_TEXT, "");
        if (!enabled) return;
        if (cmd.trim().isEmpty()) return;
        final String toRun = cmd.trim();
        runWhenShellReady(() -> sendLine(toRun));
    }

    private void showInitialCommandDialog() {
        if (!isAdded()) return;
        LayoutInflater inflater = LayoutInflater.from(requireContext());
        View content = inflater.inflate(R.layout.dialog_terminal_initial_command, (ViewGroup) getView(), false);

        final com.google.android.material.materialswitch.MaterialSwitch switchEnable = content.findViewById(R.id.switch_enable);
        final com.google.android.material.textfield.TextInputEditText et = content.findViewById(R.id.input_cmd);
        final com.google.android.material.chip.ChipGroup chips = content.findViewById(R.id.chips_initial_cmd);
        final com.google.android.material.button.MaterialButton btnCancel = content.findViewById(R.id.btn_cancel);
        final com.google.android.material.button.MaterialButton btnSave = content.findViewById(R.id.btn_save);

        SharedPreferences prefs = requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        boolean enabled = prefs.getBoolean(KEY_PREF_INITIAL_CMD_ENABLED, false);
        String savedCmd = prefs.getString(KEY_PREF_INITIAL_CMD_TEXT, "");
        switchEnable.setChecked(enabled);
        et.setText(savedCmd);
        et.setSelection(savedCmd.length());

        // Chip presets fill the command field
        if (chips != null) {
            for (int i = 0; i < chips.getChildCount(); i++) {
                View ch = chips.getChildAt(i);
                if (ch instanceof com.google.android.material.chip.Chip) {
                    com.google.android.material.chip.Chip cc = (com.google.android.material.chip.Chip) ch;
                    cc.setOnClickListener(v -> {
                        CharSequence t = cc.getText();
                        if (t != null) { et.setText(t.toString()); et.setSelection(t.length()); }
                    });
                }
            }
        }

        final BottomSheetDialog dialog = new BottomSheetDialog(requireContext(), com.google.android.material.R.style.ThemeOverlay_Material3_BottomSheetDialog);
        dialog.setContentView(content);

        btnCancel.setOnClickListener(v -> dialog.dismiss());
        btnSave.setOnClickListener(v -> {
            String cmd = et.getText() != null ? et.getText().toString().trim() : "";
            boolean en = switchEnable.isChecked();
            prefs.edit().putBoolean(KEY_PREF_INITIAL_CMD_ENABLED, en)
                    .putString(KEY_PREF_INITIAL_CMD_TEXT, cmd)
                    .apply();
            dialog.dismiss();
            Toast.makeText(requireContext(), getString(R.string.terminal_initial_command_saved), Toast.LENGTH_SHORT).show();
        });

        dialog.show();
    }

    private void sendLine(@NonNull String cmd) {
        if (serviceBound && serviceSessionId > 0) { boundService.send(serviceSessionId, cmd + "\n"); return; }
        // Fallback to legacy
        new Thread(() -> {
            try {
                if (ptyOut != null) { writePty(cmd + "\n"); }
                else if (writer != null) { writer.write(cmd + "\n"); writer.flush(); }
                else { Log.d(TAG, "[!] Shell not ready."); }
            } catch (IOException e) { Log.e(TAG, "Error sending command", e); }
        }).start();
    }

    private void copySelectedLinesToClipboard() {
        Set<Integer> selected = terminalAdapter.getSelectedLines();
        if (selected == null || selected.isEmpty()) {
            Toast.makeText(requireContext(), "No lines selected", Toast.LENGTH_SHORT).show();
            return;
        }
        // Preserve original order by sorting the selected indices
        List<Integer> positions = new ArrayList<>(selected);
        java.util.Collections.sort(positions);
        StringBuilder sb = new StringBuilder();
        for (int pos : positions) {
            CharSequence text = terminalAdapter.getLineText(pos);
            if (text != null) sb.append(text);
            sb.append('\n');
        }
        ClipboardManager clipboard = (ClipboardManager) requireContext().getSystemService(Context.CLIPBOARD_SERVICE);
        ClipData clip = ClipData.newPlainText("Terminal Output", sb.toString().trim());
        clipboard.setPrimaryClip(clip);
        Toast.makeText(requireContext(), "Copied " + positions.size() + " lines", Toast.LENGTH_SHORT).show();
        // clear selection after copy
        terminalAdapter.clearSelection();
    }

    private final TerminalService.TerminalListener serviceListener = new TerminalService.TerminalListener() {
        @Override public void onOutput(int sessionId, @NonNull TerminalService.TerminalEvent event) {
            // Parse ANSI on background thread
            ansiParserExecutor.execute(() -> {
                if (!isAdded()) return;
                appendAnsi(event.data, event.isErr);
            });
        }
        @Override public void onSessionClosed(int sessionId, int exitCode) { /* optionally notify */ }
    };

    private final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, android.os.IBinder service) {
            TerminalService.LocalBinder b = (TerminalService.LocalBinder) service;
            boundService = b.getService(); serviceBound = true;
            // Ensure a session and attach
            serviceSessionId = boundService.ensureDefaultSession(requireContext());
            boundService.attachListener(serviceSessionId, serviceListener);
            // Stop legacy local shell if it was started
            new Thread(TerminalFragment.this::stopTerminal).start();
            // Replay buffered output to reconstruct UI
            List<TerminalService.TerminalEvent> snapshot = boundService.getBufferSnapshot(serviceSessionId);
            if (snapshot != null && !snapshot.isEmpty()) {
                // Process snapshot on background thread
                ansiParserExecutor.execute(() -> {
                    for (TerminalService.TerminalEvent ev : snapshot) {
                        appendAnsi(ev.data, ev.isErr);
                    }
                });
            }
            updateCtrlButtonState();
            terminalRecycler.post(TerminalFragment.this::updatePtyWindowSize);
        }
        @Override public void onServiceDisconnected(ComponentName name) {
            if (boundService != null && serviceSessionId > 0) {
                try { boundService.detachListener(serviceSessionId, serviceListener); } catch (Throwable ignored) {}
            }
            boundService = null; serviceBound = false; serviceSessionId = -1; updateCtrlButtonState();
        }
    };
}
