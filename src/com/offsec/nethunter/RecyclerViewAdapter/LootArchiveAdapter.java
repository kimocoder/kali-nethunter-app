package com.offsec.nethunter.RecyclerViewAdapter;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;
import com.offsec.nethunter.R;
import com.offsec.nethunter.models.LootArchive;

import java.util.List;

public class LootArchiveAdapter extends RecyclerView.Adapter<LootArchiveAdapter.ViewHolder> {
    private final Context context;
    private final List<LootArchive> archives;
    private final LootArchiveListener listener;

    public interface LootArchiveListener {
        void onViewArchive(LootArchive archive);
        void onDeleteArchive(LootArchive archive);
    }

    public LootArchiveAdapter(Context context, List<LootArchive> archives, LootArchiveListener listener) {
        this.context = context;
        this.archives = archives;
        this.listener = listener;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(context).inflate(R.layout.usb_loot_item, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        LootArchive archive = archives.get(position);
        holder.tvFilename.setText(archive.getFilename());
        holder.tvSize.setText("Size: " + archive.getFormattedSize());
        holder.tvDate.setText("Collected: " + archive.getFormattedDate());

        holder.btnView.setOnClickListener(v -> listener.onViewArchive(archive));
        holder.btnDelete.setOnClickListener(v -> listener.onDeleteArchive(archive));
    }

    @Override
    public int getItemCount() {
        return archives.size();
    }

    public static class ViewHolder extends RecyclerView.ViewHolder {
        TextView tvFilename, tvSize, tvDate;
        MaterialButton btnView, btnDelete;

        public ViewHolder(View view) {
            super(view);
            tvFilename = view.findViewById(R.id.tv_loot_filename);
            tvSize = view.findViewById(R.id.tv_loot_size);
            tvDate = view.findViewById(R.id.tv_loot_date);
            btnView = view.findViewById(R.id.btn_view_archive);
            btnDelete = view.findViewById(R.id.btn_delete_archive);
        }
    }
}
