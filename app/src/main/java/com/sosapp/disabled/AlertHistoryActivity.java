package com.sosapp.disabled;

import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class AlertHistoryActivity extends AppCompatActivity {

    private ListView   listHistory;
    private TextView   tvEmpty;
    private Button     btnClearHistory;
    private DatabaseHelper dbHelper;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        applyTheme();
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_alert_history);

        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle(getString(R.string.history_title));
        }

        dbHelper       = new DatabaseHelper(this);
        listHistory    = findViewById(R.id.listHistory);
        tvEmpty        = findViewById(R.id.tvHistoryEmpty);
        btnClearHistory = findViewById(R.id.btnClearHistory);

        btnClearHistory.setOnClickListener(v -> confirmClear());
        loadHistory();
    }

    private void applyTheme() {
        android.content.SharedPreferences prefs = getSharedPreferences(SettingsActivity.PREFS_NAME, MODE_PRIVATE);
        boolean isDark = prefs.getBoolean(SettingsActivity.KEY_DARK_MODE, true);
        setTheme(isDark ? R.style.AppTheme_Dark : R.style.AppTheme_Light);
    }

    private void loadHistory() {
        List<AlertRecord> records = dbHelper.getAllAlertHistory();
        if (records.isEmpty()) {
            tvEmpty.setVisibility(View.VISIBLE);
            listHistory.setVisibility(View.GONE);
            btnClearHistory.setEnabled(false);
            return;
        }
        tvEmpty.setVisibility(View.GONE);
        listHistory.setVisibility(View.VISIBLE);
        btnClearHistory.setEnabled(true);

        HistoryAdapter adapter = new HistoryAdapter(records);
        listHistory.setAdapter(adapter);

        // Tap a row with a location → open Google Maps
        listHistory.setOnItemClickListener((parent, view, position, id) -> {
            AlertRecord rec = records.get(position);
            String loc = rec.getLocationSnap();
            if (loc != null && loc.contains(",")) {
                Intent mapIntent = new Intent(Intent.ACTION_VIEW,
                        Uri.parse("https://maps.google.com/?q=" + loc));
                try { startActivity(mapIntent); }
                catch (Exception e) {
                    Toast.makeText(this, getString(R.string.history_no_map), Toast.LENGTH_SHORT).show();
                }
            } else {
                Toast.makeText(this, getString(R.string.history_no_location), Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void confirmClear() {
        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.history_clear_title))
                .setMessage(getString(R.string.history_clear_msg))
                .setPositiveButton(getString(R.string.history_clear_confirm), (d, w) -> {
                    dbHelper.clearAlertHistory();
                    loadHistory();
                    Toast.makeText(this, getString(R.string.history_cleared), Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton(getString(R.string.cancel), null)
                .show();
    }

    @Override
    public boolean onSupportNavigateUp() {
        finish();
        return true;
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (dbHelper != null) dbHelper.close();
    }

    // ── Custom Adapter ────────────────────────────────────────────────────────

    private class HistoryAdapter extends ArrayAdapter<AlertRecord> {

        private final SimpleDateFormat sdf =
                new SimpleDateFormat("dd MMM yyyy  HH:mm:ss", Locale.getDefault());

        HistoryAdapter(List<AlertRecord> records) {
            super(AlertHistoryActivity.this, R.layout.item_history, records);
        }

        @NonNull
        @Override
        public View getView(int position, @Nullable View convertView, @NonNull ViewGroup parent) {
            if (convertView == null) {
                convertView = LayoutInflater.from(getContext())
                        .inflate(R.layout.item_history, parent, false);
            }

            AlertRecord rec = getItem(position);
            if (rec == null) return convertView;

            TextView tvDate    = convertView.findViewById(R.id.tvHistDate);
            TextView tvTrigger = convertView.findViewById(R.id.tvHistTrigger);
            TextView tvStatus  = convertView.findViewById(R.id.tvHistStatus);
            TextView tvDetail  = convertView.findViewById(R.id.tvHistDetail);

            tvDate.setText(sdf.format(new Date(rec.getTimestamp())));
            tvTrigger.setText(getString(R.string.history_trigger_label) + " " + rec.getTriggerType());

            // Status with colour coding
            String statusText = rec.getStatus();
            tvStatus.setText(statusText);
            if (AlertRecord.STATUS_SENT.equals(statusText)) {
                tvStatus.setTextColor(0xFF2E7D32);   // dark green
            } else if (AlertRecord.STATUS_CANCELLED.equals(statusText)) {
                tvStatus.setTextColor(0xFFE65100);   // orange
            } else {
                tvStatus.setTextColor(0xFFB71C1C);   // red
            }

            // Detail line: contacts + battery
            String detail = rec.getContactsNotified() + " " +
                    getString(R.string.history_contacts_label);
            if (rec.getBatteryAtTime() >= 0) {
                detail += "  •  " + getString(R.string.battery_label)
                        + " " + rec.getBatteryAtTime() + "%";
            }
            String loc = rec.getLocationSnap();
            if (loc != null && loc.contains(",")) {
                detail += "  •  " + getString(R.string.history_tap_map);
            }
            tvDetail.setText(detail);

            return convertView;
        }
    }
}
