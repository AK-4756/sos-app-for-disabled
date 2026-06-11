package com.sosapp.disabled;

import android.content.Intent;
import android.net.Uri;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * DashboardAdapter — RecyclerView adapter for the caregiver/responder dashboard.
 *
 * Each card shows:
 *   - User name + trigger type
 *   - Status badge (colour-coded)
 *   - Location with accuracy
 *   - Battery + delivered count
 *   - Action buttons gated by current responder role
 *
 * Action buttons call ActionListener so DashboardActivity handles Firebase updates.
 */
public class DashboardAdapter extends RecyclerView.Adapter<DashboardAdapter.AlertViewHolder> {

    public interface ActionListener {
        void onAcknowledge(DashboardAlertModel alert);
        void onAssignSelf(DashboardAlertModel alert);
        void onMarkOnTheWay(DashboardAlertModel alert);
        void onResolve(DashboardAlertModel alert);
        void onMarkFalseAlert(DashboardAlertModel alert);
        void onOpenMap(DashboardAlertModel alert);
    }

    private final List<DashboardAlertModel> items = new ArrayList<>();
    private ActionListener actionListener;
    private boolean canAction = true;

    private static final SimpleDateFormat SDF =
            new SimpleDateFormat("dd MMM HH:mm:ss", Locale.getDefault());

    public DashboardAdapter(ActionListener listener) {
        this.actionListener = listener;
    }

    public void setCanAction(boolean can) { this.canAction = can; }

    public void setItems(List<DashboardAlertModel> newItems) {
        items.clear();
        items.addAll(newItems);
        notifyDataSetChanged();
    }

    public void updateItem(DashboardAlertModel updated) {
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).getDocumentId().equals(updated.getDocumentId())) {
                items.set(i, updated);
                notifyItemChanged(i);
                return;
            }
        }
        // New item — add at top
        items.add(0, updated);
        notifyItemInserted(0);
    }

    public void removeItem(String docId) {
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).getDocumentId().equals(docId)) {
                items.remove(i);
                notifyItemRemoved(i);
                return;
            }
        }
    }

    @NonNull
    @Override
    public AlertViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_dashboard_alert, parent, false);
        return new AlertViewHolder(v);
    }

    @Override
    public void onBindViewHolder(@NonNull AlertViewHolder h, int position) {
        DashboardAlertModel a = items.get(position);

        h.tvUserName.setText(a.getUserName());
        h.tvTrigger.setText("via " + a.getTriggerType()
                + (a.isSilentMode() ? "  🔕" : ""));
        h.tvTimestamp.setText(SDF.format(new Date(a.getTimestamp())));

        // Status badge
        h.tvStatus.setText(a.getStatusDisplay());
        h.tvStatus.setBackgroundColor(a.getStatusColor());

        // Location
        if (a.hasLocation()) {
            h.tvLocation.setText(a.getLocationDisplay());
            h.tvLocation.setVisibility(View.VISIBLE);
            h.btnMap.setVisibility(View.VISIBLE);
        } else {
            h.tvLocation.setText("Location unavailable");
            h.tvLocation.setVisibility(View.VISIBLE);
            h.btnMap.setVisibility(View.GONE);
        }

        // Battery
        h.tvBattery.setText(a.getBattery() >= 0 ? "🔋 " + a.getBattery() + "%" : "🔋 —");

        // Delivered
        h.tvDelivered.setText("✉ " + a.getContactsDelivered() + "/" + a.getContactsNotified());

        // Responder
        if (a.getResponderName() != null && !a.getResponderName().isEmpty()) {
            h.tvResponder.setText("👤 " + a.getResponderName());
            h.tvResponder.setVisibility(View.VISIBLE);
        } else {
            h.tvResponder.setVisibility(View.GONE);
        }

        // ── Action buttons — shown based on current status ────────────────────
        boolean active = a.isActive();

        h.btnAcknowledge.setVisibility(
                active && AlertRecord.STATUS_SENT.equals(a.getStatus())
                        || AlertRecord.STATUS_DELIVERED.equals(a.getStatus())
                        ? View.VISIBLE : View.GONE);

        h.btnAssign.setVisibility(
                active && AlertRecord.STATUS_ACKNOWLEDGED.equals(a.getStatus())
                        ? View.VISIBLE : View.GONE);

        h.btnOnTheWay.setVisibility(
                active && AlertRecord.STATUS_RESPONDER_ASSIGNED.equals(a.getStatus())
                        ? View.VISIBLE : View.GONE);

        h.btnResolve.setVisibility(active ? View.VISIBLE : View.GONE);

        h.btnFalseAlert.setVisibility(
                active && (AlertRecord.STATUS_CREATED.equals(a.getStatus())
                        || AlertRecord.STATUS_SENT.equals(a.getStatus()))
                        ? View.VISIBLE : View.GONE);

        if (!canAction) {
            h.btnAcknowledge.setEnabled(false);
            h.btnAssign.setEnabled(false);
            h.btnOnTheWay.setEnabled(false);
            h.btnResolve.setEnabled(false);
        }

        // Listeners
        h.btnAcknowledge.setOnClickListener(v -> { if (actionListener != null) actionListener.onAcknowledge(a); });
        h.btnAssign.setOnClickListener(v ->      { if (actionListener != null) actionListener.onAssignSelf(a); });
        h.btnOnTheWay.setOnClickListener(v ->    { if (actionListener != null) actionListener.onMarkOnTheWay(a); });
        h.btnResolve.setOnClickListener(v ->     { if (actionListener != null) actionListener.onResolve(a); });
        h.btnFalseAlert.setOnClickListener(v ->  { if (actionListener != null) actionListener.onMarkFalseAlert(a); });
        h.btnMap.setOnClickListener(v -> {
            if (a.hasLocation() && actionListener != null) actionListener.onOpenMap(a);
        });
    }

    @Override
    public int getItemCount() { return items.size(); }

    // ── ViewHolder ────────────────────────────────────────────────────────────

    static class AlertViewHolder extends RecyclerView.ViewHolder {
        TextView tvUserName, tvTrigger, tvTimestamp, tvStatus;
        TextView tvLocation, tvBattery, tvDelivered, tvResponder;
        Button   btnAcknowledge, btnAssign, btnOnTheWay, btnResolve, btnFalseAlert, btnMap;

        AlertViewHolder(View v) {
            super(v);
            tvUserName    = v.findViewById(R.id.tvDashUserName);
            tvTrigger     = v.findViewById(R.id.tvDashTrigger);
            tvTimestamp   = v.findViewById(R.id.tvDashTime);
            tvStatus      = v.findViewById(R.id.tvDashStatus);
            tvLocation    = v.findViewById(R.id.tvDashLocation);
            tvBattery     = v.findViewById(R.id.tvDashBattery);
            tvDelivered   = v.findViewById(R.id.tvDashDelivered);
            tvResponder   = v.findViewById(R.id.tvDashResponder);
            btnAcknowledge = v.findViewById(R.id.btnDashAck);
            btnAssign     = v.findViewById(R.id.btnDashAssign);
            btnOnTheWay   = v.findViewById(R.id.btnDashOnTheWay);
            btnResolve    = v.findViewById(R.id.btnDashResolve);
            btnFalseAlert = v.findViewById(R.id.btnDashFalseAlert);
            btnMap        = v.findViewById(R.id.btnDashMap);
        }
    }
}
