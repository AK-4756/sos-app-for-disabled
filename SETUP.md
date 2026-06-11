# SOS App for Disabled — Setup Guide (v7.0)

## 1. Firebase Setup

### 1.1 Create Project
1. Go to https://console.firebase.google.com
2. Create a new project — name it `SOSApp` (or any name)
3. Disable Google Analytics (optional)

### 1.2 Add Android App
1. Click the **Android** icon on the project home
2. Package name: `com.sosapp.disabled`
3. Download `google-services.json`
4. **Replace** `/app/google-services.json` (the placeholder file) with your downloaded file

### 1.3 Enable Firestore
1. Firebase Console → Build → Firestore Database
2. Click **Create database**
3. Select **Native mode**
4. Choose a region close to your users

### 1.4 Deploy Security Rules
1. Install Firebase CLI: `npm install -g firebase-tools`
2. `firebase login`
3. From the project root: `firebase deploy --only firestore:rules`
   - Or paste the contents of `firestore.rules` manually in the Firestore Rules editor

### 1.5 Enable Authentication
1. Firebase Console → Build → Authentication → Sign-in method
2. Enable **Anonymous** (for SOS users — no login required)
3. Enable **Email/Password** (for caregivers/responders who use the dashboard)

---

## 2. Android Studio Setup

1. Open Android Studio → File → Open → select the `SOSApp` folder
2. Wait for Gradle sync (requires internet; downloads ~80MB of dependencies)
3. If sync fails: File → Invalidate Caches → Restart

### 2.1 Minimum SDK
- **minSdk 21** (Android 5.0 Lollipop)
- **targetSdk 34** (Android 14)

---

## 3. First Launch Checklist

When the app first opens on a device:
1. Grant all requested permissions (Location, SMS, Microphone, Notifications, Camera)
2. Three-dot menu → **My Profile** → fill in Name and Phone (minimum required)
3. Three-dot menu → **Contacts** → add at least one emergency contact
4. (Optional) Settings → configure countdown, language, user mode

---

## 4. Responder / Caregiver Dashboard

The in-app dashboard (three-dot menu → **Dashboard**) requires:
- A responder account created via Firebase Authentication (email + password)
- The responder's UID must have a document in the `responders` Firestore collection

### Create a responder manually (Admin)
In Firestore Console → `responders` collection → Add document:
```
{
  uid:         "firebase-auth-uid-here",
  email:       "caregiver@example.com",
  displayName: "Dr. Sharma",
  role:        "CAREGIVER",   // CAREGIVER | SECURITY | VOLUNTEER | ADMIN
  active:      true,
  createdAt:   1700000000000
}
```

### Web Dashboard
Open `dashboard/index.html` in any modern browser.
Enter your Firebase `apiKey`, `projectId`, and `appId` from:
Firebase Console → Project Settings → Your apps → Web app → Config

---

## 5. Volume Button Trigger (Accessibility Service)
1. Settings → Volume Button Trigger → Enable
2. Tap **Open Accessibility Settings →**
3. Find **SOS App for Disabled** → Enable
4. Press volume up or down **4 times within 3 seconds** to trigger SOS

---

## 6. Campus QR Codes

### QR Code Format
Print QR codes for each room/zone using either format:

**Pipe format (recommended for simplicity):**
```
SOSZONE|Classroom|Block A|2|204
```
Fields: `SOSZONE | ZoneType | Building | Floor | RoomNumber`

**JSON format:**
```json
{"zone":"Lab","building":"Science Block","floor":"1","room":"101"}
```

### Zone types supported
`Classroom`, `Lab`, `Hostel`, `Library`, `Canteen`, `Gate`, `Parking`, `Medical Centre`, `Auditorium`, `Sports Ground`, `Custom`

### Workflow
1. Print the QR code and stick it near the room entrance
2. User opens app → three-dot menu → **Scan QR Location**
3. Scan the code — zone is saved with current GPS as anchor
4. Next SOS from inside that zone automatically labels the location

---

## 7. Fall Detection
- Works automatically when the app is in the foreground
- Requires **accelerometer** (+ gyroscope for better accuracy)
- On fall detection: 10-second countdown before SOS fires
- User taps **I'm OK** on the panic panel to cancel
- Threshold: 3.2g free-fall + 25 m/s² impact + 2.5s inactivity

---

## 8. Firestore Collections Reference

| Collection | Purpose |
|---|---|
| `users/{userId}` | User profile + medical info |
| `alerts/{alertId}` | SOS events with full lifecycle |
| `responders/{uid}` | Caregiver/responder accounts |
| `contacts/{id}` | Emergency contacts (user-private) |
| `emergency_history/{id}` | Archived resolved alerts |
| `volunteers/{uid}` | Volunteer registrations |
| `settings/{id}` | Global configuration |

---

## 9. Troubleshooting

| Issue | Fix |
|---|---|
| Gradle sync fails | Check internet; File → Invalidate Caches |
| Firebase not connecting | Verify `google-services.json` has correct `package_name` |
| Dashboard shows "Firebase unavailable" | Check `google-services.json` is real (not placeholder) |
| SMS not sending | Grant SMS permission; check SIM is active |
| Voice trigger not working | Grant Microphone permission; check Speech Recognition is available |
| QR scan crashes | Grant Camera permission |
| Volume trigger not working | Enable in Accessibility Settings (step 5 above) |
