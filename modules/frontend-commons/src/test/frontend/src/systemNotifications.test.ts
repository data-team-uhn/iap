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
import { askToNotify, notificationsAllowed, notify } from "@iap/frontend-commons/systemNotifications";
import { FakeNotification, installNotifications } from "@iap/frontend-commons/systemNotifications.fixture";

beforeEach(() => installNotifications());

afterEach(() => vi.unstubAllGlobals());

describe("systemNotifications", () => {
  it("says notifications may be shown only once the user allowed them", () => {
    expect(notificationsAllowed()).toBe(false);
    FakeNotification.permission = "granted";
    expect(notificationsAllowed()).toBe(true);
  });

  it("asks the user once, and goes by their answer", async () => {
    await expect(askToNotify()).resolves.toBe(true);
    await expect(askToNotify()).resolves.toBe(true);
    expect(FakeNotification.requestPermission).toHaveBeenCalledTimes(1);
  });

  it("does not ask again a user who refused", async () => {
    FakeNotification.permission = "denied";

    await expect(askToNotify()).resolves.toBe(false);
    expect(FakeNotification.requestPermission).not.toHaveBeenCalled();
  });

  it("asks nothing of a browser without notifications", async () => {
    vi.unstubAllGlobals();
    Reflect.deleteProperty(window, "Notification");

    expect(notificationsAllowed()).toBe(false);
    await expect(askToNotify()).resolves.toBe(false);
  });

  it("shows a notification that brings the page forward when clicked", () => {
    const focus = vi.spyOn(window, "focus").mockImplementation(() => undefined);

    notify("New notification", "Something happened", "/Notifications/one");

    const [ shown ] = FakeNotification.shown;
    expect(shown.title).toBe("New notification");
    expect(shown.options).toEqual({ body: "Something happened", tag: "/Notifications/one" });
    shown.onclick?.();
    expect(focus).toHaveBeenCalled();
    expect(shown.close).toHaveBeenCalled();
    focus.mockRestore();
  });
});
