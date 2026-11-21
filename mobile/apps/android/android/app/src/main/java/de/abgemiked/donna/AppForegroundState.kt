package com.yourcompany.donna

/**
 * #51 — Ein einziger Wahrheitswert dafür, ob die React-Native-Oberfläche ([MainActivity]) gerade sichtbar im
 * Vordergrund ist. [MainActivity.onResume]/[MainActivity.onPause] setzen ihn.
 *
 * Warum nicht `ProcessLifecycleOwner`? Der gilt prozessweit: liegt eine ANDERE Donna-Activity oben (Sprach-Overlay
 * [VoiceInputActivity], Live-Bubble-Panel), ist der Prozess RESUMED, [MainActivity] aber pausiert → der JS-Hook
 * (RN `AppState` = Host-Lifecycle von MainActivity) steht auf 'background' und zeigt nichts, der NtfyService hätte
 * die Push aber als "Vordergrund" unterdrückt → Push ginge ganz verloren. Außerdem hält der ProcessLifecycleOwner
 * nach dem Pausieren noch ~700 ms RESUMED.
 *
 * Mit diesem Flag gilt für beide Consumer exakt dieselbe Bedingung (MainActivity resumed):
 *   resumed  → nativ unterdrückt (nicht-persistent), JS zeigt
 *   !resumed → nativ zeigt, JS schweigt
 */
object AppForegroundState {
    @Volatile
    var isMainResumed: Boolean = false
        private set

    fun onMainResumed() {
        isMainResumed = true
    }

    fun onMainPaused() {
        isMainResumed = false
    }
}
