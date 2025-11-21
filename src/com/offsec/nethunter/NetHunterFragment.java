package com.offsec.nethunter;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Environment;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.offsec.nethunter.RecyclerViewAdapter.NethunterRecyclerViewAdapter;
import com.offsec.nethunter.RecyclerViewData.NethunterData;
import com.offsec.nethunter.SQL.NethunterSQL;
import com.offsec.nethunter.models.NethunterModel;
import com.offsec.nethunter.utils.NhPaths;
import com.offsec.nethunter.utils.ShellExecuter;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.SearchView;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import static com.offsec.nethunter.R.id.f_nethunter_action_search;
import static com.offsec.nethunter.R.id.f_nethunter_action_snowfall;

public class NetHunterFragment extends Fragment {
    private static final String ARG_SECTION_NUMBER = "section_number";
    private NethunterRecyclerViewAdapter nethunterRecyclerViewAdapter;
    private static final AtomicBoolean NH_FILES_COPY_SCHEDULED = new AtomicBoolean(false);
    private final android.os.Handler nhHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private Button refreshButton;
    private MenuItem snowfallButton;
    private Button addButton;
    private Button deleteButton;
    private Button moveButton;
    private SharedPreferences sharedpreferences;

    public static NetHunterFragment newInstance(int sectionNumber) {
        NetHunterFragment fragment = new NetHunterFragment();
        Bundle args = new Bundle();
        args.putInt(ARG_SECTION_NUMBER, sectionNumber);
        fragment.setArguments(args);
        return fragment;
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ensureNhFilesOnSdcard();
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.nethunter, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        androidx.core.view.MenuHost menuHost = requireActivity();
        menuHost.addMenuProvider(new androidx.core.view.MenuProvider() {
            @Override
            public void onCreateMenu(@NonNull Menu menu, @NonNull MenuInflater inflater) {
                inflater.inflate(R.menu.nethunter, menu);
                MenuItem searchItem = menu.findItem(R.id.f_nethunter_action_search);
                sharedpreferences = requireActivity().getSharedPreferences("com.offsec.nethunter", Context.MODE_PRIVATE);

                boolean iswatch = requireActivity().getPackageManager().hasSystemFeature(PackageManager.FEATURE_WATCH);
                boolean snowfall = iswatch
                        ? sharedpreferences.getBoolean("snowfall_enabled", false)
                        : sharedpreferences.getBoolean("snowfall_enabled", true);

                if (iswatch) searchItem.setVisible(false);

                snowfallButton = menu.findItem(R.id.f_nethunter_action_snowfall);
                if (snowfall) snowfallButton.setIcon(R.drawable.snowflake_trigger);
                else snowfallButton.setIcon(R.drawable.snowflake_trigger_bw);

                SearchView searchView = (SearchView) searchItem.getActionView();
                if (searchView != null) {
                    searchView.setOnSearchClickListener(v -> menu.setGroupVisible(R.id.f_nethunter_menu_group1, false));
                    searchView.setOnCloseListener(() -> {
                        menu.setGroupVisible(R.id.f_nethunter_menu_group1, true);
                        return false;
                    });
                    searchView.setOnQueryTextListener(new SearchView.OnQueryTextListener() {
                        @Override public boolean onQueryTextSubmit(String query) { return false; }
                        @Override public boolean onQueryTextChange(String newText) {
                            if (nethunterRecyclerViewAdapter != null) {
                                nethunterRecyclerViewAdapter.getFilter().filter(newText);
                            }
                            return false;
                        }
                    });
                }
            }

            @Override
            public boolean onMenuItemSelected(@NonNull MenuItem item) {
                int id = item.getItemId();
                if (id == f_nethunter_action_search) return true;
                if (id == f_nethunter_action_snowfall) {
                    trigger_snowfall();
                    return true;
                }
                return false;
            }
        }, getViewLifecycleOwner());

        RecyclerView recyclerView = view.findViewById(R.id.f_nethunter_recyclerview);
        recyclerView.setLayoutManager(new LinearLayoutManager(getContext()));
        List<NethunterModel> initList = NethunterData.getInstance().getNethunterModels(requireContext()).getValue();
        if (initList == null) initList = new ArrayList<>();
        nethunterRecyclerViewAdapter = new NethunterRecyclerViewAdapter(getContext(), initList);
        recyclerView.setAdapter(nethunterRecyclerViewAdapter);
        // Observe data changes and notify adapter
        NethunterData.getInstance().getNethunterModels(requireContext()).observe(getViewLifecycleOwner(), list -> {
            if (nethunterRecyclerViewAdapter != null) nethunterRecyclerViewAdapter.notifyDataSetChanged();
        });

        // Fix button IDs to match layout
        refreshButton = view.findViewById(R.id.f_nethunter_refreshButton);
        addButton = view.findViewById(R.id.f_nethunter_addItemButton);
        deleteButton = view.findViewById(R.id.f_nethunter_deleteItemButton);
        moveButton = view.findViewById(R.id.f_nethunter_moveItemButton);

        onRefreshItemSetup();
        onAddItemSetup();
        onDeleteItemSetup();
        onMoveItemSetup();

        // WearOS optimisation
        TextView NHDesc = view.findViewById(R.id.f_nethunter_banner2);
        LinearLayout NHButtons = view.findViewById(R.id.f_nethunter_linearlayoutBtn);
        boolean iswatch = requireActivity().getPackageManager().hasSystemFeature(PackageManager.FEATURE_WATCH);
        sharedpreferences = requireActivity().getSharedPreferences("com.offsec.nethunter", Context.MODE_PRIVATE);
        sharedpreferences.edit().putBoolean("running_on_wearos", iswatch).apply();
        if (iswatch) {
            NHDesc.setVisibility(View.GONE);
            NHButtons.setVisibility(View.GONE);
        }
    }

    @Override
    public void onStart() {
        super.onStart();
        NethunterData.getInstance().refreshData();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        refreshButton = null;
        addButton = null;
        deleteButton = null;
        moveButton = null;
        nethunterRecyclerViewAdapter = null;
    }

    private void onRefreshItemSetup(){
        refreshButton.setOnClickListener(v -> NethunterData.getInstance().refreshData());
    }

    private void ensureNhFilesOnSdcard() {
        if (!NH_FILES_COPY_SCHEDULED.compareAndSet(false, true)) {
            Log.d("NetHunterFragment", "nh_files copy already scheduled; skipping.");
            return;
        }
        if (nhFilesExists()) {
            Log.i("NetHunterFragment", "nh_files exists on SD; will perform a safe sync to ensure content is present.");
        } else {
            Log.i("NetHunterFragment", "nh_files not found on SD; will create and sync.");
        }
        Log.i("NetHunterFragment", "Deferring nh_files sync by 10s to wait for storage permission.");
        nhHandler.postDelayed(() -> {
            try {
                if (!isStoragePermissionGranted()) {
                    Log.w("NetHunterFragment", "Storage permission not granted after delay; skipping nh_files sync.");
                    return;
                }
                // Always perform a safe sync; it only adds missing/newer files
                syncNhFilesToSdcard();
                if (nhFilesExists()) {
                    Log.i("NetHunterFragment", "nh_files present on /sdcard and synced.");
                } else {
                    Log.e("NetHunterFragment", "nh_files still missing after sync attempt.");
                }
            } catch (Exception e) {
                Log.e("NetHunterFragment", "Exception while syncing nh_files: ", e);
            } finally {
                NH_FILES_COPY_SCHEDULED.set(false);
            }
        }, 10_000);
    }

    private synchronized boolean nhFilesExists() {
        File nhFilesDir = new File(NhPaths.APP_SD_FILES_PATH);
        return nhFilesDir.exists() && nhFilesDir.isDirectory();
    }

    private boolean isStoragePermissionGranted() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            return Environment.isExternalStorageManager();
        } else {
            Context ctx = getContext();
            if (ctx == null) return false;
            return androidx.core.content.ContextCompat.checkSelfPermission(
                    ctx, android.Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED;
        }
    }

    // Safely mirror internal nh_files to /sdcard/nh_files without overwriting user changes
    private void syncNhFilesToSdcard() {
        try {
            final String src = NhPaths.APP_NHFILES_PATH;
            final String dst = NhPaths.SD_PATH + "/nh_files";
            ShellExecuter exe = new ShellExecuter();

            // Ensure destination exists
            exe.RunAsRootOutput("mkdir -p '" + dst + "'");

            String bb = NhPaths.BUSYBOX != null ? NhPaths.BUSYBOX.trim() : "";
            String cmd;
            if (!bb.isEmpty()) {
                cmd = bb + " cp -au '" + src + "/.' '" + dst + "/'";
            } else {
                // Fallback: do not overwrite existing files
                cmd = "sh -c 'cp -rn " + src + "/. " + dst + "/'";
            }
            String out = exe.RunAsRootOutput(cmd);
            Log.d("NetHunterFragment", "syncNhFilesToSdcard cmd output: " + (out == null ? "" : out));
        } catch (Exception e) {
            Log.e("NetHunterFragment", "syncNhFilesToSdcard error", e);
        }
    }

    private void trigger_snowfall() {
        sharedpreferences = requireActivity().getSharedPreferences("com.offsec.nethunter", Context.MODE_PRIVATE);
        boolean iswatch = requireActivity().getPackageManager().hasSystemFeature(PackageManager.FEATURE_WATCH);
        boolean snowfall = sharedpreferences.getBoolean("snowfall_enabled", !iswatch);
        if (snowfall) {
            sharedpreferences.edit().putBoolean("snowfall_enabled", false).apply();
            snowfallButton.setIcon(R.drawable.snowflake_trigger_bw);
            Toast.makeText(requireActivity().getApplicationContext(), "Snowfall disabled. Restart app to take effect.", Toast.LENGTH_SHORT).show();
        } else {
            sharedpreferences.edit().putBoolean("snowfall_enabled", true).apply();
            snowfallButton.setIcon(R.drawable.snowflake_trigger);
            Toast.makeText(requireActivity().getApplicationContext(), "Snowfall enabled. Restart app to take effect.", Toast.LENGTH_SHORT).show();
        }
    }

    private void onAddItemSetup() {
        addButton.setOnClickListener(v -> {
            List<NethunterModel> fullList = NethunterData.getInstance().nethunterModelListFull;
            // Do not reassign fullList to keep it effectively final; use a local snapshot for titles only
            List<NethunterModel> snapshot = (fullList == null) ? new ArrayList<>() : new ArrayList<>(fullList);

            // Build titles for dropdown from snapshot
            ArrayList<String> titles = new ArrayList<>();
            for (NethunterModel model : snapshot) titles.add(model.getTitle());

            LayoutInflater inflater = LayoutInflater.from(requireContext());
            View sheet = inflater.inflate(R.layout.nethunter_add_bottomsheet, null, false);

            com.google.android.material.textfield.TextInputLayout titleTil = sheet.findViewById(R.id.add_title_til);
            com.google.android.material.textfield.TextInputLayout cmdTil = sheet.findViewById(R.id.add_command_til);
            com.google.android.material.textfield.TextInputLayout delimTil = sheet.findViewById(R.id.add_delimiter_til);
            com.google.android.material.textfield.TextInputEditText titleEt = sheet.findViewById(R.id.add_title_et);
            com.google.android.material.textfield.TextInputEditText cmdEt = sheet.findViewById(R.id.add_command_et);
            com.google.android.material.textfield.TextInputEditText delimEt = sheet.findViewById(R.id.add_delimiter_et);
            com.google.android.material.checkbox.MaterialCheckBox runCb = sheet.findViewById(R.id.add_run_checkbox);

            android.widget.AutoCompleteTextView targetActv = sheet.findViewById(R.id.add_target_actv);
            com.google.android.material.button.MaterialButtonToggleGroup posGroup = sheet.findViewById(R.id.add_position_group);

            ArrayAdapter<String> adapter = new ArrayAdapter<>(requireContext(), android.R.layout.simple_list_item_1, titles);
            targetActv.setAdapter(adapter);

            // Defaults
            if (!titles.isEmpty()) targetActv.setText(titles.get(0), false);
            posGroup.check(R.id.add_after);

            com.google.android.material.bottomsheet.BottomSheetDialog dialog = new com.google.android.material.bottomsheet.BottomSheetDialog(requireContext());
            dialog.setContentView(sheet);

            View cancelBtn = sheet.findViewById(R.id.add_cancel_btn);
            View saveBtn = sheet.findViewById(R.id.add_save_btn);

            cancelBtn.setOnClickListener(x -> dialog.dismiss());
            saveBtn.setOnClickListener(x -> {
                // Clear previous errors
                titleTil.setError(null);
                cmdTil.setError(null);
                delimTil.setError(null);

                String titleString = titleEt.getText() == null ? "" : titleEt.getText().toString().trim();
                String commandString = cmdEt.getText() == null ? "" : cmdEt.getText().toString().trim();
                String delimiterString = delimEt.getText() == null ? "" : delimEt.getText().toString().trim();
                String runOnCreateString = runCb.isChecked() ? "1" : "0";

                boolean hasError = false;
                if (titleString.isEmpty()) { titleTil.setError("Required"); hasError = true; }
                if (commandString.isEmpty()) { cmdTil.setError("Required"); hasError = true; }
                if (delimiterString.isEmpty()) { delimTil.setError("Required"); hasError = true; }
                if (hasError) return;

                // Use current data size at click time to avoid captured non-final variable
                List<NethunterModel> current = NethunterData.getInstance().nethunterModelListFull;
                int size = (current == null) ? 0 : current.size();

                String tgtTitle = String.valueOf(targetActv.getText());
                int targetItemIndex = titles.indexOf(tgtTitle); // -1 if not found in snapshot

                boolean placeBefore = posGroup.getCheckedButtonId() == R.id.add_before;

                int targetPositionId; // 1-based for DB
                if (size == 0) {
                    targetPositionId = 1; // first insert
                } else {
                    if (targetItemIndex < 0) {
                        Toast.makeText(requireContext(), "Select a target item", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    // 0-based
                    targetPositionId = targetItemIndex + 1 + (placeBefore ? 0 : 1);
                    if (targetPositionId < 1) targetPositionId = 1;
                    if (targetPositionId > size + 1) targetPositionId = size + 1;
                }

                ArrayList<String> dataArrayList = new ArrayList<>();
                dataArrayList.add(titleString);
                dataArrayList.add(commandString);
                dataArrayList.add(delimiterString);
                dataArrayList.add(runOnCreateString);

                NethunterData.getInstance().addData(targetPositionId, dataArrayList, NethunterSQL.getInstance(requireContext()));
                dialog.dismiss();
            });

            dialog.show();
        });
    }

    private void onMoveItemSetup() {
        moveButton.setOnClickListener(v -> {
            List<NethunterModel> fullList = NethunterData.getInstance().nethunterModelListFull;
            if (fullList == null || fullList.isEmpty()) {
                Toast.makeText(requireContext(), "Nothing to move.", Toast.LENGTH_SHORT).show();
                return;
            }

            // Build titles for dropdowns
            ArrayList<String> titles = new ArrayList<>();
            for (NethunterModel model : fullList) titles.add(model.getTitle());

            // Inflate bottom sheet layout
            LayoutInflater inflater = LayoutInflater.from(requireContext());
            View sheet = inflater.inflate(R.layout.nethunter_move_bottomsheet, null, false);

            // Setup dropdown adapters
            ArrayAdapter<String> adapter = new ArrayAdapter<>(requireContext(), android.R.layout.simple_list_item_1, titles);
            android.widget.AutoCompleteTextView sourceActv = sheet.findViewById(R.id.move_source_actv);
            android.widget.AutoCompleteTextView targetActv = sheet.findViewById(R.id.move_target_actv);
            sourceActv.setAdapter(adapter);
            targetActv.setAdapter(adapter);

            // Sensible defaults: source first item, target second (or first if only one)
            int defaultSource = 0;
            int defaultTarget = Math.min(1, titles.size() - 1);
            if (!titles.isEmpty()) sourceActv.setText(titles.get(defaultSource), false);
            if (!titles.isEmpty()) targetActv.setText(titles.get(defaultTarget), false);

            com.google.android.material.button.MaterialButtonToggleGroup posGroup = sheet.findViewById(R.id.move_position_group);
            // Default to After for a more natural append-like move
            posGroup.check(R.id.move_after);

            com.google.android.material.bottomsheet.BottomSheetDialog dialog = new com.google.android.material.bottomsheet.BottomSheetDialog(requireContext());
            dialog.setContentView(sheet);

            View cancelBtn = sheet.findViewById(R.id.move_cancel_btn);
            View confirmBtn = sheet.findViewById(R.id.move_confirm_btn);

            cancelBtn.setOnClickListener(x -> dialog.dismiss());
            confirmBtn.setOnClickListener(x -> {
                String srcTitle = String.valueOf(sourceActv.getText());
                String tgtTitle = String.valueOf(targetActv.getText());

                if (srcTitle.isEmpty()) { sourceActv.setError("Select an item"); return; }
                if (tgtTitle.isEmpty()) { targetActv.setError("Select a target"); return; }

                int originalIndex = adapter.getPosition(srcTitle);
                int targetItemIndex = adapter.getPosition(tgtTitle);
                if (originalIndex < 0 || targetItemIndex < 0) {
                    Toast.makeText(requireContext(), "Invalid selection", Toast.LENGTH_SHORT).show();
                    return;
                }

                boolean placeBefore = posGroup.getCheckedButtonId() == R.id.move_before;
                int targetIndex = targetItemIndex + (placeBefore ? 0 : 1);

                // Clamp to [0, size]
                int size = fullList.size();
                if (targetIndex < 0) targetIndex = 0;
                if (targetIndex > size) targetIndex = size;

                // No-op checks: moving an item before itself or after just itself yields same order
                if (originalIndex == targetItemIndex && (placeBefore || originalIndex + 1 == targetIndex)) {
                    Toast.makeText(requireContext(), "No change", Toast.LENGTH_SHORT).show();
                    dialog.dismiss();
                    return;
                }

                NethunterData.getInstance().moveData(originalIndex, targetIndex, NethunterSQL.getInstance(requireContext()));
                dialog.dismiss();
            });

            dialog.show();
        });
    }

    private void onDeleteItemSetup(){
        deleteButton.setOnClickListener(v -> {
            List<NethunterModel> fullList = NethunterData.getInstance().nethunterModelListFull;
            if (fullList == null || fullList.isEmpty()) {
                Toast.makeText(requireContext(), "Nothing to delete.", Toast.LENGTH_SHORT).show();
                return;
            }

            // Build titles
            ArrayList<String> titles = new ArrayList<>();
            for (NethunterModel model : fullList) titles.add(model.getTitle());

            // Inflate bottom sheet
            LayoutInflater inflater = LayoutInflater.from(requireContext());
            View sheet = inflater.inflate(R.layout.nethunter_delete_bottomsheet, null, false);
            LinearLayout container = sheet.findViewById(R.id.delete_list_container);

            // Add a checkbox per item
            for (int i = 0; i < titles.size(); i++) {
                com.google.android.material.checkbox.MaterialCheckBox cb = new com.google.android.material.checkbox.MaterialCheckBox(requireContext());
                cb.setText(titles.get(i));
                cb.setChecked(false);
                cb.setTag(i); // store 0-based position index
                container.addView(cb);
            }

            com.google.android.material.bottomsheet.BottomSheetDialog dialog = new com.google.android.material.bottomsheet.BottomSheetDialog(requireContext());
            dialog.setContentView(sheet);

            View cancelBtn = sheet.findViewById(R.id.delete_cancel_btn);
            View confirmBtn = sheet.findViewById(R.id.delete_confirm_btn);

            cancelBtn.setOnClickListener(x -> dialog.dismiss());
            confirmBtn.setOnClickListener(x -> {
                ArrayList<Integer> selectedPositionsIndex = new ArrayList<>();
                ArrayList<Integer> selectedTargetIds = new ArrayList<>();

                final int childCount = container.getChildCount();
                for (int i = 0; i < childCount; i++) {
                    View child = container.getChildAt(i);
                    if (child instanceof com.google.android.material.checkbox.MaterialCheckBox) {
                        com.google.android.material.checkbox.MaterialCheckBox cb = (com.google.android.material.checkbox.MaterialCheckBox) child;
                        if (cb.isChecked()) {
                            Object tag = cb.getTag();
                            if (tag instanceof Integer) {
                                int pos = (Integer) tag; // 0-based adapter/index
                                selectedPositionsIndex.add(pos);
                                selectedTargetIds.add(pos + 1); // DB id is 1-based
                            }
                        }
                    }
                }

                if (selectedPositionsIndex.isEmpty()) {
                    Toast.makeText(requireContext(), "Select at least one item", Toast.LENGTH_SHORT).show();
                    return;
                }

                NethunterData.getInstance().deleteData(selectedPositionsIndex, selectedTargetIds, NethunterSQL.getInstance(requireContext()));
                dialog.dismiss();
            });

            dialog.show();
        });
    }
}
