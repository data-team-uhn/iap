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

import { render, screen, waitFor } from "@testing-library/react";

import { MessagesProvider, forgetMessages, loadMessages, useMessage } from "@iap/frontend-commons/messages";

// Shows one message, so a test can read what the hook returned off the screen
function Greeting({ messageKey }: { messageKey: string }) {
  const message = useMessage();
  return <span data-testid="greeting">{message(messageKey)}</span>;
}

describe("messages", () => {
  const answerWith = (messages: Record<string, string>, ok = true) => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok,
      json: () => Promise.resolve({ catalog: "iap.interface", locale: "en", messages }),
    });
    vi.stubGlobal("fetch", fetchMock);
    return fetchMock;
  };

  beforeEach(() => {
    forgetMessages();
    vi.unstubAllGlobals();
  });

  it("shows a message from the catalog", async () => {
    answerWith({ "iap.login.credentialsForm.username.label": "Username" });

    render(
      <MessagesProvider>
        <Greeting messageKey="iap.login.credentialsForm.username.label" />
      </MessagesProvider>,
    );

    await waitFor(() => expect(screen.getByTestId("greeting")).toHaveTextContent("Username"));
  });

  it("renders nothing until the catalog has arrived", () => {
    // Deliberate: rendering first would show the keys, or the English source, and then swap. The second
    // is the worse one — it makes an untranslated page look exactly like a translated page that has not
    // loaded yet, including to the pseudo-locale check, whose whole job is to notice English on screen.
    vi.stubGlobal("fetch", vi.fn().mockReturnValue(new Promise(() => {})));

    const { container } = render(
      <MessagesProvider>
        <Greeting messageKey="iap.anything" />
      </MessagesProvider>,
    );

    expect(container).toBeEmptyDOMElement();
  });

  it("answers with the key when the catalog has no such message", async () => {
    answerWith({ "iap.something.else": "Other" });

    render(
      <MessagesProvider>
        <Greeting messageKey="iap.missing.key" />
      </MessagesProvider>,
    );

    await waitFor(() => expect(screen.getByTestId("greeting")).toHaveTextContent("iap.missing.key"));
  });

  it("still renders when the catalog cannot be fetched", async () => {
    // An untranslated deployment, or a server having a bad day, must not take the page down with it
    vi.stubGlobal("fetch", vi.fn().mockRejectedValue(new Error("no network")));

    render(
      <MessagesProvider>
        <Greeting messageKey="iap.login.credentialsForm.username.label" />
      </MessagesProvider>,
    );

    await waitFor(() =>
      expect(screen.getByTestId("greeting")).toHaveTextContent("iap.login.credentialsForm.username.label"));
  });

  it("treats a failed response as an empty catalog rather than an error", async () => {
    answerWith({}, false);

    render(
      <MessagesProvider>
        <Greeting messageKey="iap.login.credentialsForm.username.label" />
      </MessagesProvider>,
    );

    await waitFor(() =>
      expect(screen.getByTestId("greeting")).toHaveTextContent("iap.login.credentialsForm.username.label"));
  });

  it("asks for the catalog once however many callers want it", async () => {
    const fetchMock = answerWith({ "iap.a": "A" });

    await Promise.all([ loadMessages(), loadMessages(), loadMessages() ]);

    expect(fetchMock).toHaveBeenCalledTimes(1);
  });

  it("reuses a catalog it has already loaded", async () => {
    const fetchMock = answerWith({ "iap.a": "A" });

    await loadMessages();
    await loadMessages();

    expect(fetchMock).toHaveBeenCalledTimes(1);
  });

  it("names the catalog it is asking for", async () => {
    const fetchMock = answerWith({});

    await loadMessages("iap.content");

    expect(fetchMock).toHaveBeenCalledWith("/libs/iap/messages.json?catalog=iap.content");
  });
});
