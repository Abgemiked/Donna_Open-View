import React from 'react';
import {View, Text, TouchableOpacity, StyleSheet} from 'react-native';
import type {ChatMessage, WeatherCardData, MapCardData, CalendarCardData, DonnaAction} from '@donna/shared';
import {useThemedStyles, FONT, FONT_FAMILY, FONT_LARGE, RADIUS, SPACE} from '../../theme';
import type {Theme} from '../../theme';
import {WeatherCard} from './WeatherCard';
import {MarkdownText} from './MarkdownText';
import {MapCard} from './MapCard';
import {CalendarCard} from './CalendarCard';
import {FeedbackRow} from './FeedbackRow';
import {ActionList} from './ActionCards';

// ─── MessageBubble ─────────────────────────────────────────────────────────────
export function MessageBubble({
  message,
  sessionId,
  onLongPress,
  onAction,
  userMessage,
}: {
  message: ChatMessage;
  sessionId: string;
  onLongPress?: (content: string, isAssistant: boolean) => void;
  onAction?: (a: DonnaAction) => void;
  userMessage?: string;
}): React.JSX.Element {
  const bubbleStyles = useThemedStyles(makeBubbleStyles);
  const isUser = message.role === 'user';

  return (
    <View style={[bubbleStyles.outerWrap, isUser ? bubbleStyles.userOuter : bubbleStyles.donnaOuter]}>
      <View style={isUser ? bubbleStyles.userCol : bubbleStyles.donnaCol}>
        {/* Karte vor dem Text anzeigen */}
        {!isUser && message.card?.card_type === 'weather' && (
          <WeatherCard data={message.card.data as WeatherCardData} />
        )}
        {!isUser && message.card?.card_type === 'map' && (
          <MapCard data={message.card.data as MapCardData} />
        )}
        {!isUser && message.card?.card_type === 'calendar' && (
          <CalendarCard data={message.card.data as CalendarCardData} />
        )}
        {/* Nutzer: tonale Blase rechts; Donna: freier Text ohne Blase (Markdown wie bisher) */}
        {message.content ? (
          <TouchableOpacity
            onLongPress={() => onLongPress?.(message.content, !isUser)}
            delayLongPress={400}
            activeOpacity={0.88}>
            {isUser ? (
              <View style={bubbleStyles.userBubble}>
                <Text style={bubbleStyles.userText}>{message.content}</Text>
              </View>
            ) : (
              <MarkdownText text={message.content} style={bubbleStyles.donnaText} />
            )}
          </TouchableOpacity>
        ) : null}
        {/* DONNA-Welle1 Task 5: Aktions-Pillen (create_event, set_alarm, ...) */}
        {!isUser && message.actions && message.actions.length > 0 ? (
          <ActionList actions={message.actions} onAction={onAction} />
        ) : null}
        {/* Feedback nur unter Donna-Antworten — DONNA-139: userMessage für LTM */}
        {!isUser && message.content ? (
          <FeedbackRow
            content={message.content}
            sessionId={sessionId}
            messageId={(message as any).id}
            userMessage={userMessage}
          />
        ) : null}
      </View>
    </View>
  );
}
const makeBubbleStyles = (P: Theme) => StyleSheet.create({
  outerWrap: {marginBottom: SPACE.xl},
  userOuter: {alignSelf: 'flex-end', maxWidth: '82%'},
  donnaOuter: {alignSelf: 'stretch'},
  userCol: {alignItems: 'flex-end'},
  donnaCol: {gap: SPACE.sm},
  userBubble: {
    backgroundColor: P.accentContainer,
    borderRadius: RADIUS.lg,
    borderBottomRightRadius: 4,
    paddingHorizontal: SPACE.lg,
    paddingVertical: SPACE.md,
  },
  userText: {color: P.onAccentContainer, fontFamily: FONT_FAMILY.sans.regular, fontSize: FONT.body, lineHeight: 22},
  donnaText: {color: P.text, fontFamily: FONT_FAMILY.sans.regular, fontSize: FONT_LARGE.lead, lineHeight: 26},
});
