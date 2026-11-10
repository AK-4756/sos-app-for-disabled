# SOSApp: Android Personal Safety & Fall Detection System

Engineered a robust Android safety application featuring automated fall detection and offline triggers, ensuring reliable emergency alerts even in low-connectivity environments.

## Tech Stack
* **Mobile Frontend:** Java, Android SDK
* **Backend & Cloud:** Firebase Realtime Database / Cloud Messaging
* **Local Storage:** SQLite

## Architectural Impact
* **Sensor-Driven Fall Detection:** Interfaced directly with Android hardware accelerometers and gyroscopes to detect sudden kinetic changes and trigger automated emergency protocols.
* **Offline-First Reliability:** Implemented SQLite for local data persistence, ensuring users can still trigger SMS-based alerts when cloud connectivity drops.
* **Real-Time Cloud Sync:** Integrated Firebase to handle real-time location broadcasting and push notifications to emergency contacts when network access is available.

## Architecture & Project Structure

```text
SOSApp/
├── app/
│   ├── build.gradle                 # Module-level Gradle configuration
│   ├── google-services.json         # Firebase project configuration
│   └── src/
│       └── main/
│           ├── AndroidManifest.xml  # Application manifest and permissions
│           ├── java/com/sosapp/disabled/
│           │   ├── *Activity.java   # UI Controllers (MainActivity, SettingsActivity, etc.)
│           │   ├── *Manager.java    # Core Logic (FallDetectionManager, FirebaseManager, etc.)
│           │   ├── DatabaseHelper.java # Local SQLite database handler
│           │   ├── SmsDispatcher.java  # Offline SMS sending utility
│           │   └── ...              # Background services, models, and receivers
│           └── res/
│               ├── layout/          # Application UI layouts (*.xml)
│               ├── values/          # Strings, colors, and styles
│               ├── menu/            # Application navigation menus
│               └── xml/             # Accessibility and service configurations
├── build.gradle                     # Project-level Gradle configuration
└── settings.gradle                  # Gradle workspace settings
```

## Local Setup & Installation

### 1. Clone the repository
```bash
git clone [https://github.com/AK-4756/sos-app-for-disabled.git](https://github.com/AK-4756/sos-app-for-disabled.git)
cd sos-app-for-disabled
```

### 2. Open the project in Android Studio
* Launch Android Studio.
* Click on **File > Open** and select the cloned `sos-app-for-disabled` folder.

### 3. Sync Gradle
* Android Studio should automatically start syncing the project. 
* If not, click **File > Sync Project with Gradle Files** to download dependencies.

### 4. Run the application
* Connect an Android physical device with USB debugging enabled, or start an Android Virtual Device (AVD).
* Click the **Run 'app'** button in the top toolbar, or execute the following command in the terminal:
```bash
./gradlew assembleDebug
```
