package com.sosapp.disabled;

import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

public class ProfileActivity extends AppCompatActivity {

    private EditText   etFullName, etPhone, etEmail, etInstitution, etRollNumber;
    private EditText   etBloodGroupCustom, etAllergies, etMedications, etConditions;
    private EditText   etDoctorName, etDoctorPhone, etHospitalName, etEmergencyNotes;
    private RadioGroup rgDisability, rgTrigger, rgLanguage;
    private RadioButton rbDisNone, rbDisVision, rbDisHearing, rbDisMotor, rbDisCognitive;
    private RadioButton rbTrigButton, rbTrigVoice, rbTrigShake, rbTrigVolume;
    private RadioButton rbLangEn, rbLangHi;
    private Spinner    spinnerBloodGroup;
    private CheckBox   cbLargeText, cbTts, cbVibration, cbSilent, cbShareMedical;
    private Button     btnSaveProfile;
    private TextView   tvProfileStatus;

    private ProfileManager profileManager;
    private TtsManager     tts;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        applyTheme();
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_profile);

        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle(getString(R.string.profile_title));
        }

        profileManager = ProfileManager.getInstance(this);
        tts = TtsManager.getInstance(this);

        bindViews();
        setupBloodGroupSpinner();
        loadProfile();

        btnSaveProfile.setOnClickListener(v -> saveProfile());
    }

    private void bindViews() {
        etFullName        = findViewById(R.id.etProfileName);
        etPhone           = findViewById(R.id.etProfilePhone);
        etEmail           = findViewById(R.id.etProfileEmail);
        etInstitution     = findViewById(R.id.etProfileInstitution);
        etRollNumber      = findViewById(R.id.etProfileRoll);
        spinnerBloodGroup = findViewById(R.id.spinnerBloodGroup);
        etAllergies       = findViewById(R.id.etAllergies);
        etMedications     = findViewById(R.id.etMedications);
        etConditions      = findViewById(R.id.etConditions);
        etDoctorName      = findViewById(R.id.etDoctorName);
        etDoctorPhone     = findViewById(R.id.etDoctorPhone);
        etHospitalName    = findViewById(R.id.etHospitalName);
        etEmergencyNotes  = findViewById(R.id.etEmergencyNotes);
        rgDisability      = findViewById(R.id.rgDisabilityType);
        rbDisNone         = findViewById(R.id.rbDisNone);
        rbDisVision       = findViewById(R.id.rbDisVision);
        rbDisHearing      = findViewById(R.id.rbDisHearing);
        rbDisMotor        = findViewById(R.id.rbDisMotor);
        rbDisCognitive    = findViewById(R.id.rbDisCognitive);
        rgTrigger         = findViewById(R.id.rgPreferredTrigger);
        rbTrigButton      = findViewById(R.id.rbTrigButton);
        rbTrigVoice       = findViewById(R.id.rbTrigVoice);
        rbTrigShake       = findViewById(R.id.rbTrigShake);
        rbTrigVolume      = findViewById(R.id.rbTrigVolume);
        rgLanguage        = findViewById(R.id.rgProfileLanguage);
        rbLangEn          = findViewById(R.id.rbProfileLangEn);
        rbLangHi          = findViewById(R.id.rbProfileLangHi);
        cbLargeText       = findViewById(R.id.cbLargeText);
        cbTts             = findViewById(R.id.cbTts);
        cbVibration       = findViewById(R.id.cbVibration);
        cbSilent          = findViewById(R.id.cbSilentDefault);
        cbShareMedical    = findViewById(R.id.cbShareMedical);
        btnSaveProfile    = findViewById(R.id.btnSaveProfile);
        tvProfileStatus   = findViewById(R.id.tvProfileStatus);
    }

    private void setupBloodGroupSpinner() {
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, UserProfile.BLOOD_GROUPS);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerBloodGroup.setAdapter(adapter);
    }

    private void loadProfile() {
        UserProfile p = profileManager.getProfile();
        etFullName.setText(p.getFullName());
        etPhone.setText(p.getPhone());
        etEmail.setText(p.getEmail());
        etInstitution.setText(p.getInstitutionName());
        etRollNumber.setText(p.getRollNumber());
        etAllergies.setText(p.getAllergies());
        etMedications.setText(p.getCurrentMedications());
        etConditions.setText(p.getMedicalConditions());
        etDoctorName.setText(p.getDoctorName());
        etDoctorPhone.setText(p.getDoctorPhone());
        etHospitalName.setText(p.getHospitalName());
        etEmergencyNotes.setText(p.getEmergencyNotes());
        cbLargeText.setChecked(p.isUseLargeText());
        cbTts.setChecked(p.isUseTts());
        cbVibration.setChecked(p.isUseEnhancedVibration());
        cbSilent.setChecked(p.isSilentModeDefault());
        cbShareMedical.setChecked(p.isShareProfileOnSOS());

        // Blood group spinner
        for (int i = 0; i < UserProfile.BLOOD_GROUPS.length; i++) {
            if (UserProfile.BLOOD_GROUPS[i].equals(p.getBloodGroup())) {
                spinnerBloodGroup.setSelection(i);
                break;
            }
        }

        // Disability radio
        switch (p.getDisabilityType()) {
            case UserProfile.DISABILITY_VISION:    rbDisVision.setChecked(true);   break;
            case UserProfile.DISABILITY_HEARING:   rbDisHearing.setChecked(true);  break;
            case UserProfile.DISABILITY_MOTOR:     rbDisMotor.setChecked(true);    break;
            case UserProfile.DISABILITY_COGNITIVE: rbDisCognitive.setChecked(true);break;
            default:                               rbDisNone.setChecked(true);
        }

        // Trigger preference
        switch (p.getPreferredTrigger()) {
            case UserProfile.TRIGGER_PREF_VOICE:  rbTrigVoice.setChecked(true);  break;
            case UserProfile.TRIGGER_PREF_SHAKE:  rbTrigShake.setChecked(true);  break;
            case UserProfile.TRIGGER_PREF_VOLUME: rbTrigVolume.setChecked(true); break;
            default:                              rbTrigButton.setChecked(true);
        }

        // Language
        if ("hi".equals(p.getPreferredLanguage())) rbLangHi.setChecked(true);
        else rbLangEn.setChecked(true);

        updateStatusBanner(p);
    }

    private void saveProfile() {
        String name  = etFullName.getText().toString().trim();
        String phone = etPhone.getText().toString().trim();

        if (TextUtils.isEmpty(name)) {
            etFullName.setError(getString(R.string.error_name_required));
            etFullName.requestFocus();
            return;
        }
        if (TextUtils.isEmpty(phone)) {
            etPhone.setError(getString(R.string.error_phone_required));
            etPhone.requestFocus();
            return;
        }

        UserProfile p = profileManager.getProfile();
        p.setFullName(name);
        p.setPhone(phone);
        p.setEmail(etEmail.getText().toString().trim());
        p.setInstitutionName(etInstitution.getText().toString().trim());
        p.setRollNumber(etRollNumber.getText().toString().trim());
        p.setBloodGroup(UserProfile.BLOOD_GROUPS[spinnerBloodGroup.getSelectedItemPosition()]);
        p.setAllergies(etAllergies.getText().toString().trim());
        p.setCurrentMedications(etMedications.getText().toString().trim());
        p.setMedicalConditions(etConditions.getText().toString().trim());
        p.setDoctorName(etDoctorName.getText().toString().trim());
        p.setDoctorPhone(etDoctorPhone.getText().toString().trim());
        p.setHospitalName(etHospitalName.getText().toString().trim());
        p.setEmergencyNotes(etEmergencyNotes.getText().toString().trim());
        p.setUseLargeText(cbLargeText.isChecked());
        p.setUseTts(cbTts.isChecked());
        p.setUseEnhancedVibration(cbVibration.isChecked());
        p.setSilentModeDefault(cbSilent.isChecked());
        p.setShareProfileOnSOS(cbShareMedical.isChecked());

        // Disability
        int disId = rgDisability.getCheckedRadioButtonId();
        if      (disId == R.id.rbDisVision)    p.setDisabilityType(UserProfile.DISABILITY_VISION);
        else if (disId == R.id.rbDisHearing)   p.setDisabilityType(UserProfile.DISABILITY_HEARING);
        else if (disId == R.id.rbDisMotor)     p.setDisabilityType(UserProfile.DISABILITY_MOTOR);
        else if (disId == R.id.rbDisCognitive) p.setDisabilityType(UserProfile.DISABILITY_COGNITIVE);
        else                                   p.setDisabilityType(UserProfile.DISABILITY_NONE);

        // Trigger
        int trigId = rgTrigger.getCheckedRadioButtonId();
        if      (trigId == R.id.rbTrigVoice)  p.setPreferredTrigger(UserProfile.TRIGGER_PREF_VOICE);
        else if (trigId == R.id.rbTrigShake)  p.setPreferredTrigger(UserProfile.TRIGGER_PREF_SHAKE);
        else if (trigId == R.id.rbTrigVolume) p.setPreferredTrigger(UserProfile.TRIGGER_PREF_VOLUME);
        else                                  p.setPreferredTrigger(UserProfile.TRIGGER_PREF_BUTTON);

        // Language
        p.setPreferredLanguage(rbLangHi.isChecked() ? "hi" : "en");

        profileManager.saveProfile(p);
        tts.speak(getString(R.string.tts_profile_saved));
        Toast.makeText(this, getString(R.string.profile_saved_msg), Toast.LENGTH_SHORT).show();
        updateStatusBanner(p);
        finish();
    }

    private void updateStatusBanner(UserProfile p) {
        if (p.isProfileComplete()) {
            tvProfileStatus.setText(getString(R.string.profile_complete, p.getFullName()));
            tvProfileStatus.setTextColor(ContextCompat.getColor(this, android.R.color.holo_green_dark));
        } else {
            tvProfileStatus.setText(getString(R.string.profile_incomplete));
            tvProfileStatus.setTextColor(ContextCompat.getColor(this, android.R.color.holo_orange_dark));
        }
    }

    @Override
    public boolean onSupportNavigateUp() { finish(); return true; }

    private void applyTheme() {
        android.content.SharedPreferences prefs = getSharedPreferences(SettingsActivity.PREFS_NAME, MODE_PRIVATE);
        boolean isDark = prefs.getBoolean(SettingsActivity.KEY_DARK_MODE, true);
        setTheme(isDark ? R.style.AppTheme_Dark : R.style.AppTheme_Light);
    }
}
