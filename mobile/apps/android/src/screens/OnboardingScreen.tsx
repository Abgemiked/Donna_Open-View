/**
 * OnboardingScreen — Ersteinrichtungs-Assistent (Permission-Setup-Wizard).
 *
 * Sequenzielle Checkliste über alle Berechtigungen/Spezialzugriffe (Katalog: native/DonnaSetup.ts,
 * native Prüfung/Intents: DonnaSetupModule.kt). Pro Schritt: Status, ein Satz Erklärung und ein
 * "Einrichten"-Button, der direkt den passenden System-Dialog öffnet. Der Status wird beim Öffnen,
 * nach jedem Schritt und bei jeder Rückkehr in die App (AppState → active) neu geprüft.
 *
 * mode='initial': Pflicht-Gate vor dem Chat. "Weiter zu Donna" gibt es erst, wenn alle
 *                 Pflicht-Schritte erledigt sind; dann wird `onboarding_done` gesetzt.
 *                 Notausgang: "Später einrichten" (persistiert `onboarding_deferred`, setzt
 *                 NICHT `onboarding_done`) — niemand wird aus dem Chat ausgesperrt.
 * mode='rerun':   aus dem Chat erneut geöffnet (Overlay), jederzeit schließbar.
 *
 * Funktionen-/Erweitert-Schritte lassen sich mit "Nicht jetzt" überspringen (AsyncStorage);
 * "Alles eingerichtet" gilt für Pflicht + Funktionen, Erweitert wird getrennt als "Optional" geführt.
 */
import React, {useCallback, useEffect, useMemo, useRef, useState} from 'react';
import {
  ActivityIndicator,
  Alert,
  AppState,
  BackHandler,
  ScrollView,
  StatusBar,
  StyleSheet,
  Text,
  TouchableOpacity,
  View,
} from 'react-native';
import {useSafeAreaInsets} from 'react-native-safe-area-context';
import {useTheme} from '../theme';
import type {Theme} from '../theme';
import {
  SETUP_GROUP_SUBTITLES,
  SETUP_GROUP_TITLES,
  SETUP_STEPS,
  getSetupStatusWithTimeout,
  isAllSetUp,
  isRequiredComplete,
  isStepDone,
  isStepLocked,
  isStepSkipped,
  isStepUnavailable,
  missingRequired,
  nextStep,
  openBixbyGuide,
  openCoreSteps,
  openOptionalSteps,
  requestSetupStep,
  stepsOfGroup,
  type SetupGroup,
  type SetupStatus,
  type SetupStepDef,
  type SetupStepKey,
} from '../native/DonnaSetup';
import VoiceSettings from '../components/VoiceSettings';
import TtsVoiceSettings from '../components/TtsVoiceSettings';
import LiveBubbleSettings from '../components/LiveBubbleSettings';
import {
  loadSkippedSteps,
  markOnboardingDeferred,
  markOnboardingDone,
  saveSkippedSteps,
} from '../utils/onboardingGate';

// ─── Theme ────────────────────────────────────────────────────────────────────
// UX-01: Farben aus der zentralen Theme-Quelle (src/theme) — Dark/Light + System-/Material-You-Farben.
type Palette = Theme;

const MIN_TOUCH = 48;

export interface OnboardingScreenProps {
  mode?: 'initial' | 'rerun';
  /** Wizard verlassen: "Weiter zu Donna" / "Fertig" / Schließen. */
  onFinish: () => void;
}

interface StepTexts {
  statusLabel: string;
  buttonLabel: string | null;
  tone: 'done' | 'open' | 'warn' | 'muted';
  skipped?: boolean;
}

/** Wartezeit auf den nativen Status im Screen; danach Fehlerzustand mit "Erneut prüfen". */
const SCREEN_STATUS_TIMEOUT_MS = 8000;
/** Hartes Limit: so lange darf ein Schritt "busy" bleiben, solange die App im Vordergrund ist. */
const BUSY_TIMEOUT_MS = 10000;

function describeStep(
  status: SetupStatus,
  def: SetupStepDef,
  skipped: readonly SetupStepKey[] = [],
): StepTexts {
  const s = status[def.key];
  if (isStepDone(status, def.key)) {
    return {
      statusLabel: s && !s.available ? 'Auf dieser Android-Version nicht nötig' : 'Erledigt',
      buttonLabel: null,
      tone: 'done',
    };
  }
  if (isStepUnavailable(status, def.key)) {
    return {statusLabel: 'Auf diesem Gerät nicht verfügbar', buttonLabel: null, tone: 'muted'};
  }
  if (isStepSkipped(status, skipped, def)) {
    // Automatisch übersprungen, weil der Vorgänger übersprungen wurde → erst dort "Doch einrichten"
    const viaDependency = !skipped.includes(def.key);
    const dep = SETUP_STEPS.find(d => d.key === def.dependsOn);
    return {
      statusLabel: viaDependency ? `Übersprungen (${dep?.title ?? 'Voraussetzung'} übersprungen)` : 'Übersprungen',
      buttonLabel: viaDependency ? null : 'Doch einrichten',
      tone: 'muted',
      skipped: true,
    };
  }
  if (isStepLocked(status, def)) {
    const dep = SETUP_STEPS.find(d => d.key === def.dependsOn);
    return {
      statusLabel: `Zuerst „${dep?.title ?? 'vorherigen Schritt'}“ erlauben`,
      buttonLabel: 'Einrichten',
      tone: 'muted',
    };
  }
  if (s?.blocked) {
    return {
      statusLabel: def.blockedLabel ?? 'Abgelehnt – in den App-Einstellungen erlauben',
      buttonLabel: 'Einstellungen öffnen',
      tone: 'warn',
    };
  }
  return {statusLabel: 'Offen', buttonLabel: 'Einrichten', tone: 'open'};
}

function errorMessage(e: unknown): string {
  const err = e as {code?: string; message?: string} | undefined;
  switch (err?.code) {
    case 'E_PRECONDITION':
      return err.message ?? 'Zuerst den vorherigen Schritt erledigen.';
    case 'E_UNAVAILABLE':
      return 'Auf diesem Gerät nicht verfügbar.';
    case 'E_NO_ACTIVITY':
      return 'Bitte Donna in den Vordergrund holen und erneut tippen.';
    case 'E_NO_INTENT':
      return 'Die passende Einstellungsseite wurde nicht gefunden. Bitte in den Android-Einstellungen suchen.';
    default:
      return 'Das hat nicht geklappt. Bitte erneut versuchen.';
  }
}

export default function OnboardingScreen({
  mode = 'initial',
  onFinish,
}: OnboardingScreenProps): React.JSX.Element {
  const c: Palette = useTheme();
  const scheme = c.scheme;
  const styles = useMemo(() => makeStyles(c), [c]);
  const insets = useSafeAreaInsets();

  const [status, setStatus] = useState<SetupStatus | null>(null);
  const [loadError, setLoadError] = useState(false);
  const [busyKey, setBusyKey] = useState<SetupStepKey | null>(null);
  const [stepError, setStepError] = useState<{key: SetupStepKey; message: string} | null>(null);
  const [skipped, setSkipped] = useState<SetupStepKey[]>([]);
  const [failedRequired, setFailedRequired] = useState(false);
  const mounted = useRef(true);
  const refreshSeq = useRef(0);

  const refresh = useCallback(async () => {
    // Sequenz-Guard: nur die zuletzt gestartete Prüfung darf den Status setzen
    const seq = ++refreshSeq.current;
    try {
      const s = await getSetupStatusWithTimeout(SCREEN_STATUS_TIMEOUT_MS);
      if (!mounted.current || seq !== refreshSeq.current) {
        return;
      }
      if (s == null) {
        console.warn('[Onboarding] Status-Prüfung: Timeout');
        setLoadError(true);
      } else {
        setStatus(s);
        setLoadError(false);
      }
    } catch (e) {
      console.warn('[Onboarding] Status-Prüfung fehlgeschlagen:', e);
      if (mounted.current && seq === refreshSeq.current) {
        setLoadError(true);
      }
    }
  }, []);

  // Erstprüfung + Neuprüfung bei jeder Rückkehr in die App (onResume-Äquivalent)
  useEffect(() => {
    mounted.current = true;
    void refresh();
    void loadSkippedSteps().then(keys => {
      if (mounted.current) {
        setSkipped(keys);
      }
    });
    const sub = AppState.addEventListener('change', next => {
      if (next === 'active') {
        // Hängt der native Callback (geteilter Permission-Listener), darf kein Button gesperrt bleiben
        setBusyKey(null);
        void refresh();
      }
    });
    return () => {
      mounted.current = false;
      sub.remove();
    };
  }, [refresh]);

  // Harter Timeout gegen hängende Schritte: bleibt die App 10 s im Vordergrund und der Schritt
  // "busy", werden alle Buttons wieder frei. Ist der Nutzer in einem System-Dialog/Settings
  // (App nicht aktiv), läuft die Uhr nicht; bei Rückkehr wird sofort freigegeben.
  useEffect(() => {
    if (busyKey == null) {
      return undefined;
    }
    let timer: ReturnType<typeof setTimeout> | undefined = setTimeout(
      () => setBusyKey(null),
      BUSY_TIMEOUT_MS,
    );
    const sub = AppState.addEventListener('change', next => {
      if (timer !== undefined) {
        clearTimeout(timer);
        timer = undefined;
      }
      if (next === 'active') {
        setBusyKey(null);
      }
    });
    return () => {
      if (timer !== undefined) {
        clearTimeout(timer);
      }
      sub.remove();
    };
  }, [busyKey]);

  // Hardware-Zurück: im Overlay-Modus schließen, im Gate-Modus Standardverhalten (App verlassen)
  useEffect(() => {
    if (mode !== 'rerun') {
      return undefined;
    }
    const handler = BackHandler.addEventListener('hardwareBackPress', () => {
      onFinish();
      return true;
    });
    return () => handler.remove();
  }, [mode, onFinish]);

  const runStep = useCallback(
    async (key: SetupStepKey) => {
      setBusyKey(key);
      setStepError(null);
      // "Doch einrichten": Überspringen wieder aufheben
      if (skipped.includes(key)) {
        const next = skipped.filter(k => k !== key);
        setSkipped(next);
        void saveSkippedSteps(next);
      }
      try {
        const result = await requestSetupStep(key);
        if (mounted.current) {
          setStatus(prev => ({
            ...(prev ?? {}),
            [key]: {
              granted: result.granted,
              available: result.available,
              blocked: result.blocked,
            },
          }));
        }
      } catch (e) {
        if (mounted.current) {
          setStepError({key, message: errorMessage(e)});
          if (SETUP_STEPS.find(d => d.key === key)?.group === 'required') {
            setFailedRequired(true);
          }
        }
      } finally {
        if (mounted.current) {
          setBusyKey(null);
        }
      }
      void refresh();
    },
    [refresh, skipped],
  );

  const skipStep = useCallback(
    (key: SetupStepKey) => {
      const next = skipped.includes(key) ? skipped : [...skipped, key];
      setSkipped(next);
      void saveSkippedSteps(next);
    },
    [skipped],
  );

  // Notausgang: Chat öffnen, OHNE onboarding_done zu setzen; Gate sperrt danach nicht mehr
  const deferSetup = useCallback(async () => {
    await markOnboardingDeferred();
    onFinish();
  }, [onFinish]);

  const onPressStep = useCallback(
    (def: SetupStepDef) => {
      if (def.confirm) {
        Alert.alert(def.confirm.title, def.confirm.message, [
          {text: 'Abbrechen', style: 'cancel'},
          {text: 'Verstanden, einrichten', onPress: () => void runStep(def.key)},
        ]);
      } else {
        void runStep(def.key);
      }
    },
    [runStep],
  );

  const handleContinue = useCallback(async () => {
    if (status && isRequiredComplete(status)) {
      await markOnboardingDone();
    }
    onFinish();
  }, [status, onFinish]);

  // ── Ableitungen ────────────────────────────────────────────────────────────
  const requiredTotal = stepsOfGroup('required').length;
  const requiredMissing = status ? missingRequired(status) : [];
  const requiredDone = requiredTotal - requiredMissing.length;
  const requiredComplete = status != null && requiredMissing.length === 0;
  const next = status ? nextStep(status, skipped) : undefined;
  const stillOpen = status ? openCoreSteps(status, skipped) : [];
  const optionalOpen = status ? openOptionalSteps(status, skipped) : [];
  const allDone = status != null && isAllSetUp(status, skipped);

  // ── Render ─────────────────────────────────────────────────────────────────
  const renderStep = (def: SetupStepDef) => {
    if (!status) {
      return null;
    }
    const t = describeStep(status, def, skipped);
    const isNext = next?.key === def.key;
    const locked = isStepLocked(status, def);
    const busy = busyKey === def.key;
    const disabled = locked || busyKey != null;
    const toneColor =
      t.tone === 'done' ? c.ok : t.tone === 'warn' ? c.warning : t.tone === 'open' ? c.accent : c.muted;
    const canSkip = def.group !== 'required' && t.tone !== 'done' && !t.skipped && t.buttonLabel != null;
    return (
      <View
        key={def.key}
        testID={`step-${def.key}`}
        style={[styles.row, isNext && styles.rowNext]}>
        <View style={styles.rowHead}>
          <Text
            style={[styles.stateIcon, {color: toneColor}]}
            importantForAccessibility="no-hide-descendants"
            accessibilityElementsHidden>
            {t.tone === 'done' ? '✓' : t.tone === 'warn' ? '⚠' : '○'}
          </Text>
          <View style={styles.rowTexts}>
            <Text style={styles.rowTitle}>{def.title}</Text>
            <Text style={styles.rowDesc}>{def.description}</Text>
            {def.hint != null && t.tone !== 'done' && (
              <Text style={styles.rowHint}>{def.hint}</Text>
            )}
            <Text style={[styles.rowStatus, {color: toneColor}]}>
              {isNext ? `${t.statusLabel} – als Nächstes` : t.statusLabel}
            </Text>
            {stepError?.key === def.key && t.tone !== 'done' && (
              <Text style={styles.rowError} accessibilityLiveRegion="polite">
                {stepError.message}
              </Text>
            )}
          </View>
        </View>
        {t.buttonLabel != null && (
          <TouchableOpacity
            style={[
              styles.stepButton,
              isNext ? styles.stepButtonPrimary : styles.stepButtonSecondary,
              disabled && styles.buttonDisabled,
            ]}
            onPress={() => onPressStep(def)}
            disabled={disabled}
            accessibilityRole="button"
            accessibilityLabel={`${def.title} ${
              t.buttonLabel === 'Einstellungen öffnen' ? 'in den Einstellungen öffnen' : 'einrichten'
            }`}
            accessibilityState={{disabled, busy}}>
            {busy ? (
              <ActivityIndicator color={isNext ? c.onAccent : c.accent} />
            ) : (
              <Text
                style={[
                  styles.stepButtonText,
                  isNext ? styles.stepButtonTextPrimary : styles.stepButtonTextSecondary,
                  disabled && styles.buttonDisabledText,
                ]}>
                {t.buttonLabel}
              </Text>
            )}
          </TouchableOpacity>
        )}
        {canSkip && (
          <TouchableOpacity
            style={styles.skipButton}
            onPress={() => skipStep(def.key)}
            disabled={busyKey != null}
            accessibilityRole="button"
            accessibilityLabel={`${def.title} nicht jetzt einrichten`}>
            <Text style={styles.skipButtonText}>Nicht jetzt</Text>
          </TouchableOpacity>
        )}
      </View>
    );
  };

  const renderGroup = (group: SetupGroup) => (
    <View key={group} style={styles.group}>
      <Text style={styles.groupTitle} accessibilityRole="header">
        {SETUP_GROUP_TITLES[group]}
      </Text>
      <Text style={styles.groupSubtitle}>{SETUP_GROUP_SUBTITLES[group]}</Text>
      {stepsOfGroup(group).map(renderStep)}
      {group === 'advanced' && (
        <View style={styles.row} testID="step-bixby">
          <View style={styles.rowHead}>
            <Text
              style={[styles.stateIcon, {color: c.muted}]}
              importantForAccessibility="no-hide-descendants"
              accessibilityElementsHidden>
              ℹ
            </Text>
            <View style={styles.rowTexts}>
              <Text style={styles.rowTitle}>Bixby-Schnellbefehl</Text>
              <Text style={styles.rowDesc}>
                Optional: „Hey Bixby, öffne Donna“ einrichten (Anleitung, kein Berechtigungs-Schritt).
              </Text>
            </View>
          </View>
          <TouchableOpacity
            style={[styles.stepButton, styles.stepButtonSecondary]}
            onPress={() => void openBixbyGuide()}
            accessibilityRole="button"
            accessibilityLabel="Anleitung für den Bixby-Schnellbefehl öffnen">
            <Text style={[styles.stepButtonText, styles.stepButtonTextSecondary]}>Anleitung</Text>
          </TouchableOpacity>
        </View>
      )}
    </View>
  );

  // Footer-Hauptaktion: erst der nächste offene Pflicht-Schritt, danach "Weiter zu Donna".
  const footerStep = requiredComplete ? undefined : requiredMissing[0];
  const footerPrimaryLabel = requiredComplete
    ? mode === 'rerun'
      ? 'Fertig'
      : 'Weiter zu Donna'
    : footerStep
      ? `Weiter: ${footerStep.title} einrichten`
      : 'Pflicht-Schritt einrichten';
  const footerDisabled = busyKey != null || (!requiredComplete && !footerStep);

  const onFooterPrimary = () => {
    if (requiredComplete) {
      void handleContinue();
    } else if (footerStep) {
      onPressStep(footerStep);
    }
  };

  const nextRecommended =
    requiredComplete && next && next.group === 'features' ? next : undefined;

  return (
    <View style={styles.root} testID="onboarding-screen">
      <StatusBar
        barStyle={scheme === 'light' ? 'dark-content' : 'light-content'}
        backgroundColor="transparent"
        translucent
      />
      <View style={[styles.header, {paddingTop: insets.top + 12}]}>
        <View style={styles.headerTexts}>
          <Text style={styles.title} accessibilityRole="header">
            Donna einrichten
          </Text>
          <Text style={styles.subtitle}>
            {mode === 'initial'
              ? 'Einmalig ca. 2 Minuten. Jeder Schritt öffnet direkt den passenden Dialog.'
              : 'Hier kannst du jederzeit Berechtigungen prüfen und nachholen.'}
          </Text>
        </View>
        {mode === 'rerun' && (
          <TouchableOpacity
            style={styles.closeButton}
            onPress={() => void handleContinue()}
            accessibilityRole="button"
            accessibilityLabel="Einrichtung schließen">
            <Text style={styles.closeText}>✕</Text>
          </TouchableOpacity>
        )}
      </View>

      {status == null ? (
        <View style={styles.center}>
          {loadError ? (
            <>
              <Text style={styles.rowDesc} accessibilityLiveRegion="polite">
                Der Status konnte nicht geprüft werden.
              </Text>
              <TouchableOpacity
                style={[styles.stepButton, styles.stepButtonPrimary, styles.retryButton]}
                onPress={() => void refresh()}
                accessibilityRole="button"
                accessibilityLabel="Status erneut prüfen">
                <Text style={[styles.stepButtonText, styles.stepButtonTextPrimary]}>Erneut prüfen</Text>
              </TouchableOpacity>
              {mode === 'initial' && (
                <TouchableOpacity
                  style={styles.footerLink}
                  onPress={onFinish}
                  accessibilityRole="button"
                  accessibilityLabel="Trotzdem fortfahren und zu Donna wechseln">
                  <Text style={styles.footerLinkMuted}>Trotzdem fortfahren</Text>
                </TouchableOpacity>
              )}
            </>
          ) : (
            <>
              <ActivityIndicator color={c.accent} />
              <Text style={styles.rowDesc}>Status wird geprüft …</Text>
            </>
          )}
        </View>
      ) : (
        <>
          <ScrollView
            style={styles.scroll}
            contentContainerStyle={styles.scrollContent}
            keyboardShouldPersistTaps="handled">
            <View
              style={styles.progressBox}
              accessible
              accessibilityRole="progressbar"
              accessibilityLabel="Pflicht-Schritte"
              accessibilityValue={{min: 0, max: requiredTotal, now: requiredDone}}>
              <Text style={styles.progressText}>
                {`Pflicht: ${requiredDone} von ${requiredTotal} erledigt`}
              </Text>
              <View style={styles.progressTrack}>
                <View
                  style={[
                    styles.progressFill,
                    {width: `${Math.round((requiredDone / requiredTotal) * 100)}%`},
                  ]}
                />
              </View>
            </View>

            {(['required', 'features', 'advanced'] as SetupGroup[]).map(renderGroup)}

            {/* A2: Live-Bubble (schwebender Chat-Head über allen Apps) an/aus */}
            <LiveBubbleSettings />
            {/* TB4: serverseitige Stimm-/Modell-Wahl (ElevenLabs Eva/Elisabeth) — ersetzt „Icon lange drücken" */}
            <TtsVoiceSettings />
            {/* Voice-Batch: Engine + Edge-Fallback-Stimme (A/B Edge ↔ Piper-lokal) + eager Piper-Download */}
            <VoiceSettings />

            <View style={styles.summary} testID="setup-summary">
              {allDone ? (
                <Text style={[styles.summaryTitle, {color: c.ok}]} accessibilityLiveRegion="polite">
                  ✓ Alles eingerichtet
                </Text>
              ) : (
                <>
                  <Text style={styles.summaryTitle}>Noch offen</Text>
                  {stillOpen.map(d => (
                    <Text key={d.key} style={styles.summaryItem}>
                      {`• ${d.title}${d.group === 'required' ? ' (Pflicht)' : ''}`}
                    </Text>
                  ))}
                </>
              )}
              {optionalOpen.length > 0 && (
                <Text style={[styles.summaryItem, styles.summaryOptional]}>
                  {`Optional (erweitert): ${optionalOpen.map(d => d.title).join(', ')}`}
                </Text>
              )}
            </View>
          </ScrollView>

          <View style={[styles.footer, {paddingBottom: insets.bottom + 12}]}>
            {!requiredComplete && (
              <Text style={styles.footerHint} accessibilityLiveRegion="polite">
                {`Noch ${requiredMissing.length} Pflicht-Schritt${requiredMissing.length === 1 ? '' : 'e'} offen – danach geht es weiter zu Donna.`}
              </Text>
            )}
            <TouchableOpacity
              style={[styles.footerButton, footerDisabled && styles.buttonDisabled]}
              onPress={onFooterPrimary}
              disabled={footerDisabled}
              accessibilityRole="button"
              accessibilityLabel={footerPrimaryLabel}
              accessibilityState={{disabled: footerDisabled}}>
              <Text style={styles.footerButtonText}>{footerPrimaryLabel}</Text>
            </TouchableOpacity>
            {mode === 'initial' && !requiredComplete && (
              <TouchableOpacity
                style={styles.footerLink}
                onPress={() => void deferSetup()}
                accessibilityRole="button"
                accessibilityLabel="Später einrichten und zu Donna wechseln"
                accessibilityHint="Du kannst die Einrichtung jederzeit über das Zahnrad im Chat fortsetzen.">
                <Text style={styles.footerLinkMuted}>
                  {failedRequired ? 'Klappt nicht? Später einrichten' : 'Später einrichten'}
                </Text>
              </TouchableOpacity>
            )}
            {nextRecommended != null && (
              <TouchableOpacity
                style={styles.footerLink}
                onPress={() => onPressStep(nextRecommended)}
                disabled={busyKey != null}
                accessibilityRole="button"
                accessibilityLabel={`Empfohlen: ${nextRecommended.title} einrichten`}>
                <Text style={styles.footerLinkText}>
                  {`Empfohlen: ${nextRecommended.title} einrichten`}
                </Text>
              </TouchableOpacity>
            )}
          </View>
        </>
      )}
    </View>
  );
}

function makeStyles(c: Palette) {
  return StyleSheet.create({
    root: {flex: 1, backgroundColor: c.bg},
    header: {
      flexDirection: 'row',
      alignItems: 'flex-start',
      paddingHorizontal: 20,
      paddingBottom: 8,
    },
    headerTexts: {flex: 1},
    title: {color: c.text, fontSize: 24, fontWeight: '700'},
    subtitle: {color: c.muted, fontSize: 14, lineHeight: 20, marginTop: 4},
    closeButton: {
      minWidth: MIN_TOUCH,
      minHeight: MIN_TOUCH,
      alignItems: 'center',
      justifyContent: 'center',
    },
    closeText: {color: c.text, fontSize: 20},
    center: {flex: 1, alignItems: 'center', justifyContent: 'center', gap: 12, padding: 24},
    retryButton: {alignSelf: 'center', paddingHorizontal: 24},
    scroll: {flex: 1},
    scrollContent: {paddingHorizontal: 16, paddingBottom: 24},
    progressBox: {paddingHorizontal: 4, paddingVertical: 12},
    progressText: {color: c.text, fontSize: 15, fontWeight: '600', marginBottom: 8},
    progressTrack: {
      height: 8,
      borderRadius: 4,
      backgroundColor: c.disabled,
      overflow: 'hidden',
    },
    progressFill: {height: 8, borderRadius: 4, backgroundColor: c.ok},
    group: {marginTop: 12},
    groupTitle: {color: c.accent, fontSize: 16, fontWeight: '700', marginHorizontal: 4},
    groupSubtitle: {color: c.muted, fontSize: 13, lineHeight: 18, marginHorizontal: 4, marginBottom: 8},
    row: {
      backgroundColor: c.card,
      borderRadius: 16,
      borderWidth: 1,
      borderColor: c.border,
      padding: 14,
      marginBottom: 10,
    },
    rowNext: {borderColor: c.accent, borderWidth: 2},
    rowHead: {flexDirection: 'row', alignItems: 'flex-start'},
    stateIcon: {width: 28, fontSize: 20, fontWeight: '700', lineHeight: 24},
    rowTexts: {flex: 1},
    rowTitle: {color: c.text, fontSize: 16, fontWeight: '600'},
    rowDesc: {color: c.muted, fontSize: 14, lineHeight: 20, marginTop: 2},
    rowHint: {color: c.warning, fontSize: 13, lineHeight: 18, marginTop: 4},
    rowStatus: {fontSize: 13, fontWeight: '600', marginTop: 6},
    rowError: {color: c.warning, fontSize: 13, lineHeight: 18, marginTop: 6},
    stepButton: {
      minHeight: MIN_TOUCH,
      minWidth: MIN_TOUCH,
      borderRadius: 24,
      alignItems: 'center',
      justifyContent: 'center',
      marginTop: 10,
      paddingHorizontal: 20,
    },
    stepButtonPrimary: {backgroundColor: c.accent},
    stepButtonSecondary: {borderWidth: 1.5, borderColor: c.accent},
    stepButtonText: {fontSize: 15, fontWeight: '700'},
    stepButtonTextPrimary: {color: c.onAccent},
    stepButtonTextSecondary: {color: c.accent},
    buttonDisabled: {opacity: 0.45},
    buttonDisabledText: {color: c.disabledText},
    summary: {
      marginTop: 12,
      padding: 16,
      borderRadius: 16,
      backgroundColor: c.card,
      borderWidth: 1,
      borderColor: c.border,
    },
    summaryTitle: {color: c.text, fontSize: 17, fontWeight: '700', marginBottom: 4},
    summaryItem: {color: c.muted, fontSize: 14, lineHeight: 22},
    footer: {
      paddingHorizontal: 16,
      paddingTop: 10,
      borderTopWidth: 1,
      borderTopColor: c.border,
      backgroundColor: c.bg,
    },
    footerHint: {color: c.muted, fontSize: 13, textAlign: 'center', marginBottom: 8},
    footerButton: {
      minHeight: 52,
      borderRadius: 26,
      backgroundColor: c.accent,
      alignItems: 'center',
      justifyContent: 'center',
      paddingHorizontal: 20,
    },
    footerButtonText: {color: c.onAccent, fontSize: 16, fontWeight: '700', textAlign: 'center'},
    footerLink: {
      minHeight: MIN_TOUCH,
      alignItems: 'center',
      justifyContent: 'center',
      marginTop: 4,
    },
    footerLinkText: {color: c.accent, fontSize: 15, fontWeight: '600'},
    footerLinkMuted: {color: c.muted, fontSize: 14, textDecorationLine: 'underline'},
    skipButton: {
      minHeight: MIN_TOUCH,
      alignItems: 'center',
      justifyContent: 'center',
      marginTop: 2,
    },
    skipButtonText: {color: c.muted, fontSize: 14, fontWeight: '600'},
    summaryOptional: {marginTop: 8},
  });
}
