package com.offsec.nethunter;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.ListView;
import android.widget.ProgressBar;
import androidx.appcompat.widget.SearchView;
import android.widget.Spinner;
import android.widget.TextView;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.offsec.nethunter.utils.NhPaths;
import com.offsec.nethunter.utils.ShellExecuter;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.channels.FileChannel;
import java.util.List;
import java.util.Locale;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.core.view.MenuHost;
import androidx.core.view.MenuProvider;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;

public class SearchSploitFragment extends Fragment {
    public static final String TAG = "SearchSploitFragment";
    private static final String ARG_SECTION_NUMBER = "section_number";
    private static final String PREFS_NAME = "nethunter_prefs";
    private static final String PREF_FIRST_RUN_KEY = "searchsploit_first_run";
    private Boolean withFilters = true;
    private String sel_type;
    private String sel_platform;
    private String sel_search = "";
    private TextView numex;
    private AlertDialog adi;
    private Boolean isLoaded = false;
    private ListView searchSploitListView;
    private List<SearchSploit> full_exploitList;
    private SearchSploitSQL database;
    private Context context;
    private Activity activity;
    private View rootView;

    public static SearchSploitFragment newInstance(int sectionNumber) {
        SearchSploitFragment fragment = new SearchSploitFragment();
        Bundle args = new Bundle();

        args.putInt(ARG_SECTION_NUMBER, sectionNumber);
        fragment.setArguments(args);
        return fragment;
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        context = getContext();
        activity = getActivity();
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        rootView = inflater.inflate(R.layout.searchsploit, container, false);

        database = new SearchSploitSQL(context);
        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(activity, R.style.DialogStyleCompat);
        builder.setTitle("Exploit Database Archive");
        builder.setMessage("Loading...wait");

        adi = builder.create();
        adi.setCancelable(false);
        adi.show();
        // Search Bar
        numex = rootView.findViewById(R.id.numex);
        final SearchView searchStr = rootView.findViewById(R.id.searchSploit_searchbar);
        searchStr.setOnQueryTextListener(new SearchView.OnQueryTextListener() {
            @Override
            public boolean onQueryTextSubmit(String query) {
                if (query.length() > 1) {
                    sel_search = query;
                } else {
                    sel_search = "";
                }
                loadExploits();
                return false;
            }

            @Override
            public boolean onQueryTextChange(String query) {
                if (query.isEmpty()) {
                    sel_search = "";
                    loadExploits();
                }

                return false;
            }
        });
        // Load/reload database button
        final ProgressBar progressBar = rootView.findViewById(R.id.progressBar);
        //prevents menu stuck
        rootView.postDelayed(() -> initUi(rootView), 250);

        return rootView;
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        // First run setup
        maybeShowFirstRunSetup();

        MenuHost menuHost = requireActivity();
        menuHost.addMenuProvider(new MenuProvider() {
            @Override
            public void onCreateMenu(@NonNull Menu menu, @NonNull MenuInflater inflater) {
                inflater.inflate(R.menu.searchsploit, menu);
                MenuItem raw = menu.findItem(R.id.rawSearch_ON);
                if (raw != null) {
                    raw.setTitle(withFilters ? "Enable Raw search" : "Disable Raw search");
                }
            }

            @Override
            public boolean onMenuItemSelected(@NonNull MenuItem item) {
                int id = item.getItemId();
                if (id == R.id.rawSearch_ON) {
                    if (!withFilters) {
                        view.findViewById(R.id.search_filters).setVisibility(View.VISIBLE);
                        withFilters = true;
                        item.setTitle("Enable Raw search");
                        loadExploits();
                        hideSoftKeyboard(view);
                    } else {
                        new MaterialAlertDialogBuilder(activity, R.style.DialogStyleCompat)
                                .setTitle("Raw search warning")
                                .setMessage("The exploit db is pretty big (+30K exploits), activating raw search will make the search slow.\nIs useful to do global searches when you don't find a exploit.")
                                .setNegativeButton("Cancel", (d,i)->d.dismiss())
                                .setPositiveButton("Enable", (d,i)->{
                                    view.findViewById(R.id.search_filters).setVisibility(View.GONE);
                                    item.setTitle("Disable Raw search");
                                    withFilters = false;
                                    loadExploits();
                                    hideSoftKeyboard(view);
                                })
                                .setCancelable(false)
                                .show();
                    }
                    return true;
                } else if (id == R.id.action_setup_searchsploit) {
                    showSetupDialog();
                    return true;
                } else if (id == R.id.action_reload_searchsploit) {
                    loadDatabase();
                    return true;
                }
                return false;
            }
        }, getViewLifecycleOwner(), androidx.lifecycle.Lifecycle.State.RESUMED);
    }

    private void maybeShowFirstRunSetup() {
        if (!isAdded()) return;
        SharedPreferences sp = requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        boolean firstRun = sp.getBoolean(PREF_FIRST_RUN_KEY, true);
        if (!firstRun) return;

        sp.edit().putBoolean(PREF_FIRST_RUN_KEY, false).apply();

        new MaterialAlertDialogBuilder(requireActivity(), R.style.DialogStyleCompat)
                .setTitle("SearchSploit setup")
                .setMessage("SearchSploit needs two dependencies to work, install now?\nThis will run:\napt update && apt install exploitdb python3-six")
                .setNegativeButton("Cancel", (d,i)-> d.dismiss())
                .setPositiveButton("Setup", (d,i)-> openTerminalWithCommand())
                .setCancelable(false)
                .show();
    }

    private void showSetupDialog() {
        if (!isAdded()) return;
        new MaterialAlertDialogBuilder(requireActivity(), R.style.DialogStyleCompat)
                .setTitle("SearchSploit setup")
                .setMessage("Install required packages in chroot now?\nThis will run:\napt update && apt install exploitdb python3-six")
                .setNegativeButton("Cancel", (d,i)-> d.dismiss())
                .setPositiveButton("Setup", (d,i)-> openTerminalWithCommand())
                .setCancelable(false)
                .show();
    }

    // Helper to route commands through TerminalFragment (saves memory vs external NhTerm)
    private void openTerminalWithCommand() {
        if (!isAdded()) return;
        FragmentManager fm = requireActivity().getSupportFragmentManager();
        Fragment term = TerminalFragment.newInstanceWithCommand(R.id.terminal_item, "apt update && apt install exploitdb python3-six -y");
        if (fm.isStateSaved()) {
            fm.beginTransaction()
                    .replace(R.id.container, term)
                    .addToBackStack(null)
                    .commitAllowingStateLoss();
        } else {
            fm.beginTransaction()
                    .replace(R.id.container, term)
                    .addToBackStack(null)
                    .commit();
        }
    }

    private static void hideSoftKeyboard(final View caller) {
        caller.postDelayed(() -> {
            InputMethodManager imm = (InputMethodManager) caller.getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
            imm.hideSoftInputFromWindow(caller.getWindowToken(), InputMethodManager.HIDE_NOT_ALWAYS);
        }, 100);
    }

    private void initUi(final View rootView) {
        searchSploitListView = rootView.findViewById(R.id.searchResultsList);
        long exploitCount = database.getCount();
        if (exploitCount == 0) {
            rootView.findViewById(R.id.search_filters).setVisibility(View.GONE);
            adi.dismiss();
            hideSoftKeyboard(requireView());
            return;
        } else {
            rootView.findViewById(R.id.search_filters).setVisibility(View.VISIBLE);
        }

        final List<String> platformList = database.getPlatforms();
        Spinner platformSpin = rootView.findViewById(R.id.exdb_platform_spinner);
        ArrayAdapter<String> adp12 = new ArrayAdapter<>(activity, android.R.layout.simple_list_item_1, platformList);
        adp12.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        platformSpin.setAdapter(adp12);
        platformSpin.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parentView, View selectedItemView, int position, long id) {
                sel_platform = platformList.get(position);
                loadExploits();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parentView) {
            }
        });

        final List<String> typeList = database.getTypes();
        Spinner typeSpin = rootView.findViewById(R.id.exdb_type_spinner);
        ArrayAdapter<String> adp13 = new ArrayAdapter<>(activity, android.R.layout.simple_list_item_1, typeList);
        adp13.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        typeSpin.setAdapter(adp13);
        typeSpin.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parentView, View selectedItemView, int position, long id) {
                sel_type = typeList.get(position);
                loadExploits();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parentView) {
            }
        });
        // Initialize selections to first items
        if (!platformList.isEmpty()) {
            sel_platform = platformList.get(0);
        }
        if (!typeList.isEmpty()) {
            sel_type = typeList.get(0);
        }
        full_exploitList = database.getAllExploits();
        loadExploits();
    }

    private void loadExploits() {
        if ((sel_platform != null) && (sel_type != null)) {
            List<SearchSploit> exploitList;
            if (withFilters) {
                exploitList = database.getAllExploitsFiltered(sel_search, sel_type, sel_platform);
            } else {
                if (sel_search.isEmpty()) {
                    exploitList = full_exploitList;
                } else {
                    exploitList = database.getAllExploitsRaw(sel_search);
                }
            }
            if (exploitList == null) {
                new Handler(Looper.getMainLooper()).postDelayed(this::loadExploits, 1500);
                return;
            }
            numex.setText(String.format(Locale.getDefault(),"%d results", exploitList.size()));
            ExploitLoader exploitAdapter = new ExploitLoader(context, exploitList);
            searchSploitListView.setAdapter(exploitAdapter);
            if (!isLoaded) {

                adi.dismiss();
                isLoaded = true;
                hideSoftKeyboard(requireView());
            }
        }
    }

    private void loadDatabase() {
        final ProgressBar progressBar = rootView.findViewById(R.id.progressBar);
        progressBar.setVisibility(View.VISIBLE);
        new Thread(() -> {
            final Boolean isFeeded = database.doDbFeed();
            requireActivity().runOnUiThread(() -> {
                if (isFeeded) {
                    NhPaths.showMessage_long(context, "DB FEED DONE");
                    try {
                        String data = NhPaths.APP_PATH + "/";
                        String DATABASE_NAME = "SearchSploit";
                        String currentDBPath = "databases/" + DATABASE_NAME;
                        // Use the NetHunter app nh_files directory instead of raw external storage
                        File backupDB = new File(data, currentDBPath);
                        File currentDB = new File(NhPaths.APP_NHFILES_PATH, DATABASE_NAME);

                        try (FileInputStream fis = new FileInputStream(currentDB);
                             FileOutputStream fos = new FileOutputStream(backupDB);
                             FileChannel src = fis.getChannel();
                             FileChannel dst = fos.getChannel()) {
                            dst.transferFrom(src, 0, src.size());
                        }
                        Log.d("importDB", "Successfully imported " + DATABASE_NAME);
                        initUi(rootView);
                    } catch (Exception e) {
                        Log.d("importDB", e.toString());
                    }
                } else {
                    NhPaths.showMessage_long(context,
                            "Unable to find SearchSploit files.csv database. Install exploitdb in chroot");
                }
                progressBar.setVisibility(View.GONE);
            });
        }).start();
    }
}

class ExploitLoader extends BaseAdapter {
    private final List<SearchSploit> _exploitList;
    private final Context _mContext;

    ExploitLoader(Context context, List<SearchSploit> exploitList) {
        _mContext = context;
        _exploitList = exploitList;
    }

    static class ViewHolderItem {
        // The switch
        //Switch sw;
        // the msg holder
        TextView type;
        TextView platform;
        TextView author;
        TextView date;
        // the service title
        TextView description;
        // run at boot checkbox
        Button viewSource;
        Button openWeb;
        Button sendHid;
    }

    public int getCount() {
        // return the number of services
        return _exploitList.size();
    }

    private void start(String file) {
        String[] command = new String[1];
        String nhpath = NhPaths.APP_PATH;
        command[0] = "su -mm -c " + nhpath + "/scripts/bootkali file2hid-file " + file;
        String test = "su -mm -c " + nhpath + "/scripts/bootkali file2hid-file " + file;
        Log.d("Exe:", test);
        ShellExecuter exe = new ShellExecuter();
        exe.RunAsRoot(command);
    }

    // getView method is called for each item of ListView
    public View getView(final int position, View convertView, ViewGroup parent) {
        // inflate the layout for each item of listView (our services)

        ViewHolderItem vH;

        if (convertView == null) {
            LayoutInflater inflater = (LayoutInflater) _mContext.getSystemService(Context.LAYOUT_INFLATER_SERVICE);
            convertView = inflater.inflate(R.layout.searchsploit_item, parent, false);

            // set up the ViewHolder
            vH = new ViewHolderItem();
            // get the reference of switch and the text view
            vH.description = convertView.findViewById(R.id.description);
            // vH.cwSwich = (Switch) convertView.findViewById(R.id.switch1);
            vH.type = convertView.findViewById(R.id.type);
            vH.platform = convertView.findViewById(R.id.platform);
            vH.author = convertView.findViewById(R.id.author);
            vH.date = convertView.findViewById(R.id.exploit_date);
            vH.viewSource = convertView.findViewById(R.id.viewSource);
            vH.openWeb = convertView.findViewById(R.id.openWeb);
            vH.sendHid = convertView.findViewById(R.id.searchsploit_sendhid_button);
            convertView.setTag(vH);
            //System.out.println ("created row");
        } else {
            // recycle the items in the list if already exists
            vH = (ViewHolderItem) convertView.getTag();
        }

        // remove listeners
        final SearchSploit exploitItem = getItem(position);

        final String _file = exploitItem.getFile();
        final long _id = exploitItem.getId();
        String _desc = exploitItem.getDescription();
        String _date = exploitItem.getDate();
        String _author = exploitItem.getAuthor();
        String _type = exploitItem.getType();
        String _platform = exploitItem.getPlatform();

        vH.viewSource.setOnClickListener(null);
        vH.openWeb.setOnClickListener(null);
        // set service name
        vH.description.setText(_desc);
        vH.type.setText(_type);
        vH.platform.setText(_platform);
        vH.author.setText(_author);
        vH.date.setText(_date);
        vH.viewSource.setOnClickListener(v -> {
            Intent i = new Intent(_mContext, EditSourceActivity.class);
            i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            i.putExtra("path", "/data/local/nhsystem/kalifs/usr/share/exploitdb/" + _file);
            _mContext.startActivity(i);

        });

        vH.sendHid.setOnClickListener(v -> {
            start("/usr/share/exploitdb/" + _file);
            //_mContext.startActivity(i);
        });

        vH.openWeb.setOnClickListener(v -> {
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            String url = "https://www.exploit-db.com/exploits/" + _id + "/";
            i.setData(Uri.parse(url));
            _mContext.startActivity(i);
        });
        return convertView;
    }

    public SearchSploit getItem(int position) {
        return _exploitList.get(position);
    }
    public long getItemId(int position) {
        return position;
    }
}
