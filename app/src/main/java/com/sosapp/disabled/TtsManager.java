package com.sosapp.disabled;

import android.content.Context;
import android.os.Build;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.util.Log;

import java.util.HashMap;
import java.util.Locale;

/**
 * Singleton Text-to-Speech manager.
 * Always call speak() — it queues safely even before TTS is ready.
 * Interrupt priority lets countdown beats cut through any ongoing speech.
 */
public class TtsManager {

    private static final String TAG = "TtsManager";
    private static TtsManager instance;

    private TextToSpeech tts;
    private boolean ready = false;
    private boolean enabled = true;   // user can silence via settings
    private float speechRate = 1.0f;  // user-adjustable

    private TtsManager(Context context) {
        tts = new TextToSpeech(context.getApplicationContext(), status -> {
            if (status == TextToSpeech.SUCCESS) {
                int result = tts.setLanguage(Locale.getDefault());
                if (result == TextToSpeech.LANG_MISSING_DATA
                        || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                    tts.setLanguage(Locale.ENGLISH);
                }
                tts.setSpeechRate(speechRate);
                ready = true;
                Log.d(TAG, "TTS ready");
            } else {
                Log.e(TAG, "TTS init failed: " + status);
            }
        });
    }

    public static synchronized TtsManager getInstance(Context context) {
        if (instance == null) {
            instance = new TtsManager(context);
        }
        return instance;
    }

    /** Normal speech — queued after any current utterance. */
    public void speak(String text) {
        if (!enabled || !ready || text == null || text.isEmpty()) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            tts.speak(text, TextToSpeech.QUEUE_ADD, null, "sos_" + System.currentTimeMillis());
        } else {
            HashMap<String, String> params = new HashMap<>();
            params.put(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, "sos_" + System.currentTimeMillis());
            //noinspection deprecation
            tts.speak(text, TextToSpeech.QUEUE_ADD, params);
        }
    }

    /** High-priority speech — interrupts any current utterance immediately. */
    public void speakNow(String text) {
        if (!enabled || !ready || text == null || text.isEmpty()) return;
        tts.stop();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "sos_priority");
        } else {
            HashMap<String, String> params = new HashMap<>();
            params.put(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, "sos_priority");
            //noinspection deprecation
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, params);
        }
    }

    public void stop() {
        if (tts != null) tts.stop();
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
        if (!enabled) stop();
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setSpeechRate(float rate) {
        this.speechRate = rate;
        if (ready) tts.setSpeechRate(rate);
    }

    public void setLocale(Locale locale) {
        if (ready) {
            int result = tts.setLanguage(locale);
            if (result == TextToSpeech.LANG_MISSING_DATA
                    || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                tts.setLanguage(Locale.ENGLISH);
            }
        }
    }

    /** Call from Application.onTerminate or when truly done. */
    public void shutdown() {
        if (tts != null) {
            tts.stop();
            tts.shutdown();
            ready = false;
        }
        instance = null;
    }
}
