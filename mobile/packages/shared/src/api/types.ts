// Donna API — Zentrale Typen-Definitionen
// Beide Apps (Android + Windows) nutzen diese Typen via @donna/shared

export interface WeatherCardData {
  location: string;
  temp_c: number;
  feels_like_c: number;
  temp_min: number;
  temp_max: number;
  condition: string;
  condition_icon: string;
  humidity: number;
  wind_kmh: number;
}

export interface MapCardData {
  query: string;
  maps_url: string;
  lat?: number | null;
  lon?: number | null;
}

export interface ChatCard {
  card_type: 'weather' | 'map';
  data: WeatherCardData | MapCardData;
}

// DONNA-Welle1 Task 5: Action-Typ — vom Backend emittierte DONNA_ACTION-Marker
// werden als strukturierte Events ans Frontend gestreamt (type: 'action').
// Das Frontend zeigt sie als Hinweise/Buttons unter der Bubble.
export interface DonnaAction {
  type: string;  // create_event | set_alarm | set_timer | navigate | call | sms | whatsapp | play_music | note | open_url
  // Restliche Felder dynamisch (Action-Typ-spezifisch)
  [key: string]: unknown;
}

export interface ChatMessage {
  role: 'user' | 'assistant';
  content: string;
  card?: ChatCard;
  actions?: DonnaAction[];
  // DONNA-115: Ideen-Karten
  ideaConfirm?: IdeaConfirmPayload;
  ideaUpdate?: IdeaUpdatePayload;
}

export interface ChatLocation {
  lat: number;
  lon: number;
}

export interface VoiceAuthChallenge {
  challenge_id: string;
  phrase: string;
}

export interface VoiceAuthVerifyRequest {
  challenge_id: string;
  nonce: string;
  timestamp: number;
  audio_hash: string;
}

export interface VoiceAuthVerifyResponse {
  status: 'ok';
}

// DONNA-115: Ideen-Bestätigungs-Payload
export interface IdeaConfirmPayload {
  title: string;
  description: string;
  tags: string[];
}

// DONNA-115: Ideen-Update-Payload (bestehende Idee erweitern)
export interface IdeaUpdatePayload {
  idea_id: string;
  title: string;
}

export interface StreamChunk {
  type: 'delta' | 'done' | 'error' | 'card' | 'action' | 'idea_confirm' | 'idea_update';
  content?: string;
  error?: string;
  card?: ChatCard;
  action?: DonnaAction;
  idea?: IdeaConfirmPayload | IdeaUpdatePayload;
}

export interface ApiError {
  status: number;
  reason?: string;
  message: string;
  retry_after?: number;
}

export class DonnaApiError extends Error {
  readonly status: number;
  readonly reason?: string;
  readonly retryAfter?: number;

  constructor(error: ApiError) {
    super(error.message);
    this.name = 'DonnaApiError';
    this.status = error.status;
    this.reason = error.reason;
    this.retryAfter = error.retry_after;
  }
}
