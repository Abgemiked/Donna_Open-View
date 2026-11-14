import {AppRegistry} from 'react-native';
import App from './src/App';
import {name as appName} from './app.json';
import notifee, {EventType} from '@notifee/react-native';

// DONNA-228/230: Background-Event-Handler für Notification-Taps wenn App geschlossen ist.
// NtfyService schreibt den Payload in SharedPreferences — dieser Handler triggert
// consumeProactiveFromPrefs() beim nächsten App-Start über den Cold-Start-Pfad.
// Der Background-Handler selbst kann nur NativeModules aufrufen wenn der JS-Thread
// noch läuft — bei echter App-Beendigung ist das nicht möglich. Daher nur Logging.
notifee.onBackgroundEvent(async ({type, detail}) => {
  if (type === EventType.PRESS) {
    // Payload wurde von NtfyService bereits in SharedPreferences geschrieben.
    // consumeProactiveFromPrefs() wird beim App-Start (Cold Start) automatisch
    // von App.tsx → ChatScreen aufgerufen. Kein weiterer Action nötig.
    console.log(
      '[index.js] Background PRESS — SharedPreferences-Payload beim nächsten Start abrufbar',
      detail.notification?.id,
    );
  }
});

AppRegistry.registerComponent(appName, () => App);
