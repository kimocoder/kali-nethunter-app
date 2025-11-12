package com.offsec.nethunter.terminal;

import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.style.BackgroundColorSpan;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;

import com.offsec.nethunter.R;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class TerminalAdapter extends ListAdapter<CharSequence, TerminalAdapter.LineVH> {
    private static final DiffUtil.ItemCallback<CharSequence> DIFF = new DiffUtil.ItemCallback<CharSequence>() {
        @Override
        public boolean areItemsTheSame(@NonNull CharSequence oldItem, @NonNull CharSequence newItem) {
            // Since terminal lines are append-only and trimmed from the head, content equality works for diffs.
            if (oldItem == newItem) return true;
            return oldItem.toString().equals(newItem.toString());
        }
        @Override
        public boolean areContentsTheSame(@NonNull CharSequence oldItem, @NonNull CharSequence newItem) {
            return oldItem.toString().contentEquals(newItem);
        }
    };

    private final List<CharSequence> linesBuffer = new ArrayList<>();
    private final int maxLines;
    private float textSizeSp = 12f; // default, can be modified at runtime
    private String highlightTerm = null;
    private int baseTextColor = Color.WHITE; // default; can be themed
    private float lineSpacingExtraPx = 0f;
    private float lineSpacingMult = 1.0f;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private boolean flushScheduled = false;
    private final Set<Integer> selectedLines = new HashSet<>();
    private Integer selectionAnchor = null;

    // Callback for selection events
    public interface SelectionListener {
        void onSelectionChanged(Set<Integer> selectedLines);
        void onLineLongClicked(int position, CharSequence text);
    }
    private SelectionListener selectionListener = null;

    public void setSelectionListener(SelectionListener l) { this.selectionListener = l; }

    public TerminalAdapter(int maxLines) {
        super(DIFF);
        this.maxLines = maxLines;
        // First submit empty list
        submitList(new ArrayList<>(linesBuffer));
    }

    public static class LineVH extends RecyclerView.ViewHolder {
        final TextView tv;
        public LineVH(@NonNull View itemView) { super(itemView); tv = itemView.findViewById(R.id.line_text); }
    }

    @NonNull
    @Override
    public LineVH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_terminal_line, parent, false);
        return new LineVH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull LineVH holder, int position) {
        CharSequence line = getItem(position);
        if (highlightTerm != null && !highlightTerm.isEmpty()) {
            SpannableStringBuilder spannable = new SpannableStringBuilder(line);
            String lineStr = line.toString().toLowerCase();
            String termLower = highlightTerm.toLowerCase();
            int index = lineStr.indexOf(termLower);
            while (index >= 0) {
                spannable.setSpan(new BackgroundColorSpan(Color.YELLOW), index, index + highlightTerm.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                index = lineStr.indexOf(termLower, index + highlightTerm.length());
            }
            holder.tv.setText(spannable);
        } else {
            holder.tv.setText(line);
        }
        // Apply current dynamic text size and base color
        holder.tv.setTextSize(textSizeSp);
        holder.tv.setTextColor(baseTextColor);
        holder.tv.setLineSpacing(lineSpacingExtraPx, lineSpacingMult);
        // Selection highlight
        holder.itemView.setSelected(selectedLines.contains(holder.getBindingAdapterPosition()));

        // Ensure previous listeners removed to avoid duplicate events
        holder.itemView.setOnClickListener(null);
        holder.itemView.setOnLongClickListener(null);

        // Short click toggles selection when there is already at least one selected line (range selection support could be added later)
        holder.itemView.setOnClickListener(v -> {
            int adapterPosition = holder.getBindingAdapterPosition();
            if (adapterPosition == RecyclerView.NO_POSITION) return;
            if (!selectedLines.isEmpty()) {
                if (selectedLines.contains(adapterPosition)) deselectLine(adapterPosition); else selectLine(adapterPosition);
                if (selectionListener != null) selectionListener.onSelectionChanged(getSelectedLines());
            }
        });

        // Long click supports range selection: if no anchor, set anchor and toggle. If anchor exists and different
        // long-pressing another line selects the full inclusive range and notifies listener.
        holder.itemView.setOnLongClickListener(v -> {
            int adapterPosition = holder.getBindingAdapterPosition();
            if (adapterPosition == RecyclerView.NO_POSITION) return false;
            if (selectionAnchor == null) {
                selectionAnchor = adapterPosition;
                if (selectedLines.contains(adapterPosition)) deselectLine(adapterPosition); else selectLine(adapterPosition);
                if (selectionListener != null) selectionListener.onLineLongClicked(adapterPosition, line);
            } else {
                int a = Math.min(selectionAnchor, adapterPosition);
                int b = Math.max(selectionAnchor, adapterPosition);
                Set<Integer> prev = new HashSet<>(selectedLines);
                selectedLines.clear();
                for (int i = a; i <= b; i++) selectedLines.add(i);
                Set<Integer> union = new HashSet<>(prev); union.addAll(selectedLines);
                for (int pos : union) notifyItemChanged(pos);
            }
            if (selectionListener != null) selectionListener.onSelectionChanged(getSelectedLines());
            return true;
        });
    }

    @Override
    public int getItemCount() { return super.getItemCount(); }

    public void addLine(CharSequence line, RecyclerView recycler) {
        // Ensure buffer size within cap, removing from head in batches
        int toRemove = (linesBuffer.size() + 1) - maxLines;
        if (toRemove > 0) {
            linesBuffer.subList(0, toRemove).clear();
        }
        linesBuffer.add(line);
        scheduleFlushAndMaybeScroll(recycler);
    }

    public void addLines(Collection<? extends CharSequence> newLines, RecyclerView recycler) {
        if (newLines == null || newLines.isEmpty()) return;
        int needed = newLines.size();
        int toRemove = (linesBuffer.size() + needed) - maxLines;
        if (toRemove > 0) {
            linesBuffer.subList(0, Math.min(toRemove, linesBuffer.size())).clear();
        }
        linesBuffer.addAll(newLines);
        scheduleFlushAndMaybeScroll(recycler);
    }

    private void scheduleFlushAndMaybeScroll(RecyclerView recycler) {
        if (!flushScheduled) {
            flushScheduled = true;
            mainHandler.postDelayed(() -> {
                flushScheduled = false;
                // Submit a copy so DiffUtil can work on a stable snapshot
                submitList(new ArrayList<>(linesBuffer));
                if (recycler != null) recycler.post(() -> recycler.scrollToPosition(getItemCount() - 1));
            }, 16); // ~1 frame delay for better batching
        }
    }

    public void clearAll() {
        if (linesBuffer.isEmpty() && getItemCount() == 0) return;
        linesBuffer.clear();
        submitList(new ArrayList<>(linesBuffer));
    }

    public List<CharSequence> getLines() {
        return new ArrayList<>(linesBuffer);
    }

    public void setTextSizeSp(float sizeSp) {
        if (sizeSp == this.textSizeSp) return;
        this.textSizeSp = sizeSp;
        submitList(getCurrentList());
    }

    public float getTextSizeSp() { return textSizeSp; }

    public void setHighlightTerm(String term) {
        this.highlightTerm = term;
        submitList(getCurrentList());
    }

    public void setBaseTextColor(int color) {
        if (this.baseTextColor == color) return;
        this.baseTextColor = color;
        submitList(getCurrentList());
    }

    public void setLineSpacing(float extraPx, float multiplier) {
        if (this.lineSpacingExtraPx == extraPx && this.lineSpacingMult == multiplier) return;
        this.lineSpacingExtraPx = extraPx;
        this.lineSpacingMult = multiplier;
        submitList(getCurrentList());
    }

    public void selectLine(int position) {
        selectedLines.add(position);
        notifyItemChanged(position);
    }
    public void deselectLine(int position) {
        selectedLines.remove(position);
        notifyItemChanged(position);
    }
    public void clearSelection() {
        Set<Integer> old = new HashSet<>(selectedLines);
        selectedLines.clear();
        selectionAnchor = null;
        for (int pos : old) notifyItemChanged(pos);
    }
    public Set<Integer> getSelectedLines() { return new HashSet<>(selectedLines); }
    public CharSequence getLineText(int position) { return getItem(position); }
}
