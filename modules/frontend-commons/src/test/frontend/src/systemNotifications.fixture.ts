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
// The browser's Notification, which jsdom does not have. It records what was shown, and its permission
// prompt answers as a test sets it to. `installNotifications` puts it in place for one test, and
// `vi.unstubAllGlobals()` removes it.

export class FakeNotification {
  static permission: NotificationPermission = "default";

  /** What the permission prompt answers. */
  static answer: NotificationPermission = "granted";

  static readonly shown: FakeNotification[] = [];

  static readonly requestPermission = vi.fn(() => {
    FakeNotification.permission = FakeNotification.answer;
    return Promise.resolve(FakeNotification.answer);
  });

  onclick: (() => void) | null = null;

  readonly close = vi.fn();

  constructor(readonly title: string, readonly options: NotificationOptions) {
    FakeNotification.shown.push(this);
  }
}

/**
 * Gives the window a Notification whose permission starts as given, with nothing shown yet.
 *
 * @param permission what the user answered so far
 */
export function installNotifications(permission: NotificationPermission = "default"): void {
  FakeNotification.permission = permission;
  FakeNotification.answer = "granted";
  FakeNotification.shown.length = 0;
  FakeNotification.requestPermission.mockClear();
  vi.stubGlobal("Notification", FakeNotification);
}
