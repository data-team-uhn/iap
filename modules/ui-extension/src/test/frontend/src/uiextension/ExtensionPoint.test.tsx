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

import ExtensionPoint from "@iap/ui-extension/ExtensionPoint";

const EXTENSION_PATH = "iap/coreUI/sidebar/entry";
const EXTENSION_URL = "/libs/iap/resources/sidebar.js";

// The component makes two hops: it asks /uixp where the extension lives, then fetches that URL and
// dispatches on the content type of what comes back.
const finderResponse = (url = EXTENSION_URL, ok = true) => ({
  ok,
  status: ok ? 200 : 404,
  text: () => Promise.resolve(url),
}) as unknown as Response;

const extensionResponse = (
  { contentType, body = "", ok = true, status = 200 }:
  { contentType: string | null; body?: string; ok?: boolean; status?: number },
) => ({
  ok,
  status,
  headers: new Headers(contentType === null ? {} : { "Content-Type": contentType }),
  text: () => Promise.resolve(body),
  json: () => Promise.resolve(JSON.parse(body || "null") as unknown),
}) as unknown as Response;

describe("ExtensionPoint", () => {
  let fetchMock: ReturnType<typeof vi.fn>;
  let errorSpy: ReturnType<typeof vi.spyOn>;

  beforeEach(() => {
    fetchMock = vi.fn();
    vi.stubGlobal("fetch", fetchMock);
    errorSpy = vi.spyOn(console, "error").mockImplementation(() => { /* keep the output quiet */ });
  });

  afterEach(() => {
    vi.unstubAllGlobals();
    vi.restoreAllMocks();
  });

  // Both hops succeed, with the extension served as the given type
  const serve = (contentType: string | null, body = "") => {
    fetchMock
      .mockResolvedValueOnce(finderResponse())
      .mockResolvedValueOnce(extensionResponse({ contentType, body }));
  };

  const expectRejection = (message: string) => waitFor(() => {
    expect(errorSpy).toHaveBeenCalledWith(expect.objectContaining({ message }) as Error);
  });

  it("asks the finder for the extension, then fetches what it points at", async () => {
    serve("text/html", "<p>Hello</p>");

    render(<ExtensionPoint path={EXTENSION_PATH} />);

    await waitFor(() => { expect(fetchMock).toHaveBeenCalledTimes(2); });
    expect((fetchMock.mock.calls[0] as [URL])[0].toString())
      .toBe(`${window.location.origin}/uixp?uixp=${EXTENSION_PATH}`);
    expect((fetchMock.mock.calls[1] as [URL])[0].toString()).toBe(`${window.location.origin}${EXTENSION_URL}`);
  });

  it("inlines an HTML extension", async () => {
    serve("text/html", "<p>Injected content</p>");

    render(<ExtensionPoint path={EXTENSION_PATH} />);

    expect(await screen.findByText("Injected content")).toBeInTheDocument();
  });

  it("hands a JSON extension to the callback", async () => {
    const callback = vi.fn();
    serve("application/json", '{"label":"Reports"}');

    render(<ExtensionPoint path={EXTENSION_PATH} callback={callback} />);

    await waitFor(() => { expect(callback).toHaveBeenCalledWith({ label: "Reports" }); });
  });

  it("rejects a JSON extension when no callback can receive it", async () => {
    serve("application/json", '{"label":"Reports"}');

    render(<ExtensionPoint path={EXTENSION_PATH} />);

    await expectRejection(
      `Fetching ExtensionPoint ${EXTENSION_PATH} returned json data, but no callback was provided to its ExtensionPoint`
    );
  });

  it.each(["text/javascript", "application/javascript"])("evaluates a %s extension", async contentType => {
    serve(contentType, "globalThis.__extensionPointProbe = 'ran';");

    render(<ExtensionPoint path={EXTENSION_PATH} />);

    await waitFor(() => {
      expect((globalThis as { __extensionPointProbe?: string }).__extensionPointProbe).toBe("ran");
    });
    delete (globalThis as { __extensionPointProbe?: string }).__extensionPointProbe;
  });

  it("ignores the charset when dispatching on the content type", async () => {
    serve("text/html;charset=utf-8", "<p>Charset handled</p>");

    render(<ExtensionPoint path={EXTENSION_PATH} />);

    expect(await screen.findByText("Charset handled")).toBeInTheDocument();
  });

  it("rejects a content type it cannot handle", async () => {
    serve("image/png");

    render(<ExtensionPoint path={EXTENSION_PATH} />);

    await expectRejection(`Fetching ExtensionPoint ${EXTENSION_PATH} returned unknown contentType: image/png`);
  });

  it("rejects a response with no content type at all", async () => {
    serve(null);

    render(<ExtensionPoint path={EXTENSION_PATH} />);

    await expectRejection(`Fetching ExtensionPoint ${EXTENSION_PATH} returned unknown contentType: `);
  });

  it("reports an extension point the finder cannot locate", async () => {
    fetchMock.mockResolvedValueOnce(finderResponse(EXTENSION_URL, false));

    render(<ExtensionPoint path={EXTENSION_PATH} />);

    await expectRejection(`Finding ExtensionPoint ${EXTENSION_PATH} failed with response 404`);
    // The extension itself was never fetched
    expect(fetchMock).toHaveBeenCalledTimes(1);
  });

  it("reports an extension that cannot be fetched", async () => {
    fetchMock
      .mockResolvedValueOnce(finderResponse())
      .mockResolvedValueOnce(extensionResponse({ contentType: "text/html", ok: false, status: 500 }));

    render(<ExtensionPoint path={EXTENSION_PATH} />);

    await expectRejection(`Fetching ExtensionPoint ${EXTENSION_PATH} failed with response 500`);
  });

  it("reports a network failure", async () => {
    fetchMock.mockRejectedValueOnce(new Error("offline"));

    render(<ExtensionPoint path={EXTENSION_PATH} />);

    await expectRejection("offline");
  });

  it("fetches the extension only once, however often it re-renders", async () => {
    serve("text/html", "<p>Once</p>");

    const { rerender } = render(<ExtensionPoint path={EXTENSION_PATH} />);
    await screen.findByText("Once");
    rerender(<ExtensionPoint path={EXTENSION_PATH} />);

    expect(fetchMock).toHaveBeenCalledTimes(2); // the finder hop and the extension hop, no more
  });
});
