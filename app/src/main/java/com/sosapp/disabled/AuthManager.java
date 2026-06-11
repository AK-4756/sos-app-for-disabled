package com.sosapp.disabled;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.FirebaseFirestore;

import java.util.HashMap;
import java.util.Map;

/**
 * AuthManager — handles Firebase Auth and role-based access control.
 *
 * Roles (stored in Firestore responders/{uid}):
 *   CAREGIVER   — can view and acknowledge alerts for specific users
 *   SECURITY    — can view all campus alerts
 *   VOLUNTEER   — can accept and respond to alerts
 *   ADMIN       — full access, can manage responders
 *
 * The SOS user themselves uses anonymous auth (no login required).
 * Caregivers/responders use email+password auth via the dashboard.
 */
public class AuthManager {

    private static final String TAG = "AuthManager";

    // ── Roles ─────────────────────────────────────────────────────────────────
    public static final String ROLE_CAREGIVER = "CAREGIVER";
    public static final String ROLE_SECURITY  = "SECURITY";
    public static final String ROLE_VOLUNTEER = "VOLUNTEER";
    public static final String ROLE_ADMIN     = "ADMIN";
    public static final String ROLE_USER      = "USER"; // SOS user, anonymous

    // ── Firestore collections ─────────────────────────────────────────────────
    public static final String COL_RESPONDERS = "responders";

    // ── Local prefs ───────────────────────────────────────────────────────────
    private static final String PREF_ROLE        = "auth_role";
    private static final String PREF_DISPLAY_NAME = "auth_display_name";
    private static final String PREF_AUTH_EMAIL  = "auth_email";

    private static AuthManager instance;
    private final Context context;
    private FirebaseAuth  firebaseAuth;

    private AuthManager(Context context) {
        this.context = context.getApplicationContext();
        try {
            firebaseAuth = FirebaseAuth.getInstance();
        } catch (Exception e) {
            Log.w(TAG, "Firebase Auth not available: " + e.getMessage());
        }
    }

    public static synchronized AuthManager getInstance(Context context) {
        if (instance == null) instance = new AuthManager(context.getApplicationContext());
        return instance;
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Anonymous auth (SOS user — no login required)
    // ══════════════════════════════════════════════════════════════════════════

    public void ensureAnonymousAuth(Runnable onComplete) {
        if (firebaseAuth == null) { if (onComplete != null) onComplete.run(); return; }
        if (firebaseAuth.getCurrentUser() != null) { if (onComplete != null) onComplete.run(); return; }

        firebaseAuth.signInAnonymously()
                .addOnSuccessListener(result -> {
                    Log.d(TAG, "Anonymous auth OK: " + result.getUser().getUid());
                    if (onComplete != null) onComplete.run();
                })
                .addOnFailureListener(e -> {
                    Log.w(TAG, "Anonymous auth failed: " + e.getMessage());
                    if (onComplete != null) onComplete.run(); // continue anyway
                });
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Responder / Caregiver login (email + password)
    // ══════════════════════════════════════════════════════════════════════════

    public interface AuthCallback {
        void onSuccess(String role, String displayName);
        void onFailure(String errorMessage);
    }

    public void loginResponder(String email, String password, AuthCallback callback) {
        if (firebaseAuth == null) {
            callback.onFailure("Firebase not available");
            return;
        }
        firebaseAuth.signInWithEmailAndPassword(email, password)
                .addOnSuccessListener(result -> {
                    FirebaseUser user = result.getUser();
                    if (user == null) { callback.onFailure("Auth error"); return; }
                    fetchResponderRole(user.getUid(), email, callback);
                })
                .addOnFailureListener(e -> callback.onFailure(e.getMessage()));
    }

    public void registerResponder(String email, String password,
                                   String displayName, String role, AuthCallback callback) {
        if (firebaseAuth == null) { callback.onFailure("Firebase not available"); return; }
        firebaseAuth.createUserWithEmailAndPassword(email, password)
                .addOnSuccessListener(result -> {
                    FirebaseUser user = result.getUser();
                    if (user == null) { callback.onFailure("Creation failed"); return; }
                    // Store role in Firestore
                    Map<String, Object> doc = new HashMap<>();
                    doc.put("uid",         user.getUid());
                    doc.put("email",       email);
                    doc.put("displayName", displayName);
                    doc.put("role",        role);
                    doc.put("active",      true);
                    doc.put("createdAt",   System.currentTimeMillis());
                    FirebaseFirestore.getInstance()
                            .collection(COL_RESPONDERS)
                            .document(user.getUid())
                            .set(doc)
                            .addOnSuccessListener(v -> {
                                saveLocalRole(role, displayName, email);
                                callback.onSuccess(role, displayName);
                            })
                            .addOnFailureListener(e -> callback.onFailure(e.getMessage()));
                })
                .addOnFailureListener(e -> callback.onFailure(e.getMessage()));
    }

    public void logout() {
        if (firebaseAuth != null) firebaseAuth.signOut();
        prefs().edit()
                .remove(PREF_ROLE)
                .remove(PREF_DISPLAY_NAME)
                .remove(PREF_AUTH_EMAIL)
                .apply();
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Current session info
    // ══════════════════════════════════════════════════════════════════════════

    public boolean isLoggedIn() {
        return firebaseAuth != null
                && firebaseAuth.getCurrentUser() != null
                && !firebaseAuth.getCurrentUser().isAnonymous();
    }

    public String getCurrentRole()        { return prefs().getString(PREF_ROLE, ROLE_USER); }
    public String getCurrentDisplayName() { return prefs().getString(PREF_DISPLAY_NAME, "User"); }
    public String getCurrentEmail()       { return prefs().getString(PREF_AUTH_EMAIL, ""); }

    public boolean canAcknowledge() {
        String role = getCurrentRole();
        return ROLE_CAREGIVER.equals(role) || ROLE_SECURITY.equals(role)
                || ROLE_VOLUNTEER.equals(role) || ROLE_ADMIN.equals(role);
    }

    public boolean isAdmin() { return ROLE_ADMIN.equals(getCurrentRole()); }

    // ══════════════════════════════════════════════════════════════════════════
    // Helpers
    // ══════════════════════════════════════════════════════════════════════════

    private void fetchResponderRole(String uid, String email, AuthCallback callback) {
        FirebaseFirestore.getInstance()
                .collection(COL_RESPONDERS)
                .document(uid)
                .get()
                .addOnSuccessListener(doc -> {
                    if (doc.exists()) {
                        String role = doc.getString("role");
                        String name = doc.getString("displayName");
                        if (role == null) role = ROLE_CAREGIVER;
                        if (name == null) name = email;
                        saveLocalRole(role, name, email);
                        callback.onSuccess(role, name);
                    } else {
                        // First login — assign default caregiver role
                        saveLocalRole(ROLE_CAREGIVER, email, email);
                        callback.onSuccess(ROLE_CAREGIVER, email);
                    }
                })
                .addOnFailureListener(e -> callback.onFailure(e.getMessage()));
    }

    private void saveLocalRole(String role, String displayName, String email) {
        prefs().edit()
                .putString(PREF_ROLE,         role)
                .putString(PREF_DISPLAY_NAME, displayName)
                .putString(PREF_AUTH_EMAIL,   email)
                .apply();
    }

    private SharedPreferences prefs() {
        return context.getSharedPreferences(SettingsActivity.PREFS_NAME, Context.MODE_PRIVATE);
    }
}
