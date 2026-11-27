/**
 * Startbildschirm-Gate (#134): Erst wenn die gespeicherte Darstellungswahl geladen ist (ThemeContext.ready), zeigt die App
 * Inhalt; bis dahin bleibt der Ladezustand. So springt beim Start nichts von Hell auf Dunkel um.
 * Ohne native Methode ist `ready` sofort true, das Gate haengt dann nie.
 */
export type GateRoute = 'loading' | 'onboarding' | 'chat';

export function effectiveRoute(route: GateRoute, themeReady: boolean): GateRoute {
  return themeReady ? route : 'loading';
}
