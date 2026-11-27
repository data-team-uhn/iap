/*
 * Copyright 2026 DATA @ UHN. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
// The browser's own notifications, outside the page, for news that arrives while the user is looking
// elsewhere. The browser asks the user's permission once, and only when a click asks for it.

/** Whether the browser may show this site's notifications. */
export const notificationsAllowed = (): boolean =>
  "Notification" in window && Notification.permission === "granted";

/**
 * Asks the user's permission to show notifications, unless they answered already. Browsers ask only
 * in answer to a click or a key, so call it from such a handler.
 *
 * @returns whether notifications may be shown
 */
export async function askToNotify(): Promise<boolean> {
  if (!("Notification" in window)) {
    return false;
  }
  if (Notification.permission === "default") {
    await Notification.requestPermission();
  }
  return Notification.permission === "granted";
}

/**
 * Shows a notification, which brings the page forward when clicked. A second one with the same tag
 * replaces the first.
 *
 * @param title the notification's heading
 * @param body its text
 * @param tag what it is about, so that one thing is announced once
 */
export function notify(title: string, body: string, tag: string): void {
  const notification = new Notification(title, { body, tag });
  notification.onclick = () => {
    window.focus();
    notification.close();
  };
}
