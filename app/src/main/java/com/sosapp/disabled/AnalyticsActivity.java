package com.sosapp.disabled;

import android.graphics.Color;
import android.os.Bundle;
import android.view.MenuItem;
import android.view.View;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.github.mikephil.charting.charts.BarChart;
import com.github.mikephil.charting.charts.PieChart;
import com.github.mikephil.charting.components.XAxis;
import com.github.mikephil.charting.data.BarData;
import com.github.mikephil.charting.data.BarDataSet;
import com.github.mikephil.charting.data.BarEntry;
import com.github.mikephil.charting.data.PieData;
import com.github.mikephil.charting.data.PieDataSet;
import com.github.mikephil.charting.data.PieEntry;
import com.github.mikephil.charting.formatter.IndexAxisValueFormatter;
import com.github.mikephil.charting.utils.ColorTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * AnalyticsActivity — campus emergency analytics dashboard.
 *
 * Displays:
 *   - Summary cards: total, resolved, false, avg response time, active volunteers
 *   - Trigger type pie chart
 *   - Hourly distribution bar chart
 *   - Top location hotspots list
 *
 * All data sourced from Firestore (last 30 days) via CampusAnalyticsManager.
 */
public class AnalyticsActivity extends AppCompatActivity {

    private ProgressBar pbAnalytics;
    private TextView    tvTotal, tvResolved, tvFalse, tvResponse, tvVolunteers;
    private TextView    tvHotspots;
    private PieChart    pieChart;
    private BarChart    barChart;

    private CampusAnalyticsManager analyticsManager;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        applyTheme();
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_analytics);

        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle(getString(R.string.analytics_title));
        }

        analyticsManager = new CampusAnalyticsManager();
        bindViews();
        loadAnalytics();
    }

    private void bindViews() {
        pbAnalytics  = findViewById(R.id.pbAnalytics);
        tvTotal      = findViewById(R.id.tvAnalyticsTotal);
        tvResolved   = findViewById(R.id.tvAnalyticsResolved);
        tvFalse      = findViewById(R.id.tvAnalyticsFalse);
        tvResponse   = findViewById(R.id.tvAnalyticsResponse);
        tvVolunteers = findViewById(R.id.tvAnalyticsVolunteers);
        tvHotspots   = findViewById(R.id.tvAnalyticsHotspots);
        pieChart     = findViewById(R.id.pieChartTriggers);
        barChart     = findViewById(R.id.barChartHourly);
    }

    private void loadAnalytics() {
        pbAnalytics.setVisibility(View.VISIBLE);

        analyticsManager.fetchAnalytics(new CampusAnalyticsManager.AnalyticsCallback() {
            @Override
            public void onResult(CampusAnalyticsManager.AnalyticsResult r) {
                runOnUiThread(() -> {
                    pbAnalytics.setVisibility(View.GONE);
                    populateSummary(r);
                    populatePieChart(r.triggerBreakdown);
                    populateBarChart(r.hourlyDistribution);
                    populateHotspots(r.locationHotspots);
                });
            }

            @Override
            public void onError(Exception e) {
                runOnUiThread(() -> {
                    pbAnalytics.setVisibility(View.GONE);
                    tvTotal.setText("Firebase unavailable");
                });
            }
        });
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Summary cards
    // ══════════════════════════════════════════════════════════════════════════

    private void populateSummary(CampusAnalyticsManager.AnalyticsResult r) {
        tvTotal.setText(String.valueOf(r.totalAlerts));
        tvResolved.setText(String.valueOf(r.resolvedAlerts));
        tvFalse.setText(String.valueOf(r.falseAlerts));
        tvResponse.setText(getString(R.string.analytics_seconds, (int) r.avgResponseTimeSec));
        tvVolunteers.setText(String.valueOf(r.activeVolunteers));
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Pie chart — trigger type breakdown
    // ══════════════════════════════════════════════════════════════════════════

    private void populatePieChart(Map<String, Integer> triggerMap) {
        if (triggerMap.isEmpty()) { pieChart.setVisibility(View.GONE); return; }

        int textColor = getThemeColor(android.R.attr.textColorPrimary);

        List<PieEntry> entries = new ArrayList<>();
        for (Map.Entry<String, Integer> e : triggerMap.entrySet()) {
            entries.add(new PieEntry(e.getValue(), e.getKey()));
        }

        PieDataSet dataSet = new PieDataSet(entries, "SOS Triggers");
        dataSet.setColors(ColorTemplate.MATERIAL_COLORS);
        dataSet.setValueTextSize(12f);
        dataSet.setValueTextColor(Color.WHITE);

        PieData data = new PieData(dataSet);
        pieChart.setData(data);
        pieChart.setUsePercentValues(true);
        pieChart.getDescription().setEnabled(false);
        pieChart.setHoleRadius(40f);
        pieChart.setTransparentCircleRadius(45f);
        pieChart.setHoleColor(Color.TRANSPARENT);
        pieChart.setCenterText("Triggers");
        pieChart.setCenterTextSize(14f);
        pieChart.setCenterTextColor(textColor);
        pieChart.getLegend().setTextSize(11f);
        pieChart.getLegend().setTextColor(textColor);
        pieChart.animateY(800);
        pieChart.invalidate();
    }

    private void populateBarChart(Map<Integer, Integer> hourlyMap) {
        int textColor = getThemeColor(android.R.attr.textColorPrimary);

        List<BarEntry> entries = new ArrayList<>();
        for (int h = 0; h < 24; h++) {
            Integer val = hourlyMap.get(h);
            entries.add(new BarEntry(h, val != null ? val : 0));
        }

        BarDataSet dataSet = new BarDataSet(entries, "Alerts by Hour");
        dataSet.setColor(0xFF1E88E5);
        dataSet.setValueTextSize(8f);
        dataSet.setValueTextColor(textColor);

        BarData data = new BarData(dataSet);
        data.setBarWidth(0.85f);

        String[] hours = new String[24];
        for (int i = 0; i < 24; i++) hours[i] = i + "h";

        barChart.setData(data);
        barChart.getDescription().setEnabled(false);
        barChart.setFitBars(true);
        barChart.getLegend().setTextColor(textColor);

        barChart.getXAxis().setValueFormatter(new IndexAxisValueFormatter(hours));
        barChart.getXAxis().setPosition(XAxis.XAxisPosition.BOTTOM);
        barChart.getXAxis().setGranularity(1f);
        barChart.getXAxis().setTextSize(9f);
        barChart.getXAxis().setTextColor(textColor);

        barChart.getAxisLeft().setTextColor(textColor);
        barChart.getAxisRight().setEnabled(false);

        barChart.animateY(600);
        barChart.invalidate();
    }

    private int getThemeColor(int attr) {
        android.util.TypedValue typedValue = new android.util.TypedValue();
        getTheme().resolveAttribute(attr, typedValue, true);
        return typedValue.data;
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Hotspots text
    // ══════════════════════════════════════════════════════════════════════════

    private void populateHotspots(Map<String, Integer> hotspots) {
        if (hotspots.isEmpty()) {
            tvHotspots.setText("No location data yet.");
            return;
        }
        StringBuilder sb = new StringBuilder();
        int rank = 1;
        for (Map.Entry<String, Integer> e : hotspots.entrySet()) {
            sb.append(rank++).append(". ").append(e.getKey())
              .append("  —  ").append(e.getValue()).append(" alerts\n");
        }
        tvHotspots.setText(sb.toString().trim());
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) { finish(); return true; }
        return super.onOptionsItemSelected(item);
    }

    private void applyTheme() {
        android.content.SharedPreferences prefs = getSharedPreferences(SettingsActivity.PREFS_NAME, MODE_PRIVATE);
        boolean isDark = prefs.getBoolean(SettingsActivity.KEY_DARK_MODE, true);
        setTheme(isDark ? R.style.AppTheme_Dark : R.style.AppTheme_Light);
    }
}
