package com.offsec.nethunter;

import android.app.Dialog;
import android.os.Bundle;
import android.widget.Button;
import android.widget.CheckBox;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.DialogFragment;
import android.view.LayoutInflater;
import android.view.View;

public class WifiteSettingsDialogFragment extends DialogFragment {
    public interface SettingsDialogListener {
        void onSettingsChanged(boolean showNetworksWithoutSSID);
    }

    private SettingsDialogListener listener;

    public void setSettingsDialogListener(SettingsDialogListener listener) {
        this.listener = listener;
    }

    @NonNull
    @Override
    public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
        AlertDialog.Builder builder = new AlertDialog.Builder(requireActivity());
        LayoutInflater inflater = requireActivity().getLayoutInflater();
        View view = inflater.inflate(R.layout.dialog_settings, null);

        CheckBox checkboxOption = view.findViewById(R.id.checkbox_option);
        Button applyButton = view.findViewById(R.id.apply_button);
        Button cancelButton = view.findViewById(R.id.cancel_button);

        applyButton.setOnClickListener(v -> {
            if (listener != null) {
                listener.onSettingsChanged(checkboxOption.isChecked());
            }
            dismiss();
        });

        cancelButton.setOnClickListener(v -> dismiss());

        builder.setView(view)
                .setTitle("Settings");

        return builder.create();
    }
}