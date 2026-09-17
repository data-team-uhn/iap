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

import { ThemeProvider } from "@mui/material/styles";
import { render, screen, waitFor } from "@testing-library/react";

import { appTheme } from "@iap/frontend-commons/appTheme";
import { SESSION_INFO_URL } from "@iap/frontend-commons/reLogin";
import BpmnEditor from "@iap/workflows/BpmnEditor";
import { EMPTY_BPMN } from "@iap/workflows/workflowModel";

// bpmn-js drives an SVG canvas that jsdom can't lay out, so both classes are stood in for. That keeps
// these tests about the component's own behaviour -- which class it picks, what it loads, what it
// hands back -- rather than about bpmn-js. Hoisted so the stand-ins exist when the mocked modules load.
const { ModelerMock, ViewerMock, instances, refuseImports } = vi.hoisted(() => {
  // What a test needs of a canvas: which class it came from, and the calls made on it
  interface Canvas {
    kind: string;
    importXML: ReturnType<typeof vi.fn>;
    saveXML: ReturnType<typeof vi.fn>;
    destroy: ReturnType<typeof vi.fn>;
    handlers: Map<string, (event: unknown) => void>;
  }
  const created: Canvas[] = [];
  // What every canvas created from now on does with the diagram it is handed
  let importFailure: Error | null = null;

  class CanvasMock {
    importXML = vi.fn(() => importFailure ? Promise.reject(importFailure) : Promise.resolve({}));
    saveXML = vi.fn().mockResolvedValue({ xml: "<saved/>" });
    destroy = vi.fn();
    handlers = new Map<string, (event: unknown) => void>();
    on = vi.fn((event: string, handler: (payload: unknown) => void) => this.handlers.set(event, handler));
    off = vi.fn((event: string) => this.handlers.delete(event));
    get = vi.fn();

    constructor(public kind: string, public options: unknown) {
      created.push(this);
    }
  }

  class ModelerMock extends CanvasMock {
    constructor(options: unknown) {
      super("modeler", options);
    }
  }

  class ViewerMock extends CanvasMock {
    constructor(options: unknown) {
      super("viewer", options);
    }
  }

  return {
    ModelerMock,
    ViewerMock,
    instances: created,
    refuseImports: (failure: Error | null) => {
      importFailure = failure;
    },
  };
});

vi.mock("bpmn-js/lib/Modeler", () => ({ default: ModelerMock }));
vi.mock("bpmn-js/lib/NavigatedViewer", () => ({ default: ViewerMock }));

const VERSION_PATH = "/Workflows/review/1-0";
const DIAGRAM = "<bpmn:definitions/>";

// A server answering the version's diagram with the given status and body.
const stubFetch = (status = 200, body: string = DIAGRAM) => {
  const fetchMock = vi.fn((url: string) => Promise.resolve((url === SESSION_INFO_URL
    ? { ok: true, status: 200, url, json: () => Promise.resolve({ userID: "admin" }) }
    : {
      ok: status < 400,
      status,
      statusText: "Refused",
      url,
      text: () => Promise.resolve(body),
    }) as unknown as Response));
  vi.stubGlobal("fetch", fetchMock);
  return fetchMock;
};

// A server that answers the session but holds the diagram back, so that a test can decide when — and
// whether — the request settles, and what the canvas is doing when it does.
const deferredFetch = () => {
  let settle: (ok: boolean) => void = () => undefined;
  vi.stubGlobal("fetch", vi.fn((url: string) => url === SESSION_INFO_URL
    ? Promise.resolve({ ok: true, status: 200, url, json: () => Promise.resolve({ userID: "admin" }) } as
      unknown as Response)
    : new Promise<Response>((resolve, reject) => {
      settle = (ok: boolean) => ok
        ? resolve({ ok: true, status: 200, url, text: () => Promise.resolve(DIAGRAM) } as unknown as Response)
        : reject(new TypeError("Failed to fetch"));
    })));
  return { settle: (ok: boolean) => settle(ok) };
};

// A serializer is handed over and taken back as the canvas comes and goes; the latest one handed over
// is the one a page would be holding.
type SerializerHandler = ReturnType<typeof vi.fn<(serialize: (() => Promise<string>) | null) => void>>;

const latestSerializer = (onReady: SerializerHandler): (() => Promise<string>) => {
  const handed = onReady.mock.calls.map(call => call[0]).filter(serialize => serialize !== null);
  const serialize = handed.at(-1);
  expect(serialize).toBeDefined();
  return serialize!;
};

const renderEditor = (props: Partial<Parameters<typeof BpmnEditor>[0]> = {}) => render(
  <ThemeProvider theme={appTheme} defaultMode="light">
    <BpmnEditor versionPath={VERSION_PATH} {...props} />
  </ThemeProvider>
);

beforeEach(() => {
  instances.length = 0;
  refuseImports(null);
});

afterEach(() => vi.unstubAllGlobals());

describe("BpmnEditor", () => {
  it("displays a diagram through a viewer that cannot edit it", async () => {
    const fetchMock = stubFetch();

    renderEditor();

    await waitFor(() => expect(instances[0].importXML).toHaveBeenCalledWith(DIAGRAM));
    // A plain viewer instance, with no editing capability at all.
    expect(instances).toHaveLength(1);
    expect(instances[0].kind).toBe("viewer");
    // The diagram is a file of its own, fetched on its own path
    expect(fetchMock.mock.calls[0][0]).toBe(`${VERSION_PATH}/bpmn.xml`);
  });

  it("uses the modeler when the diagram is to be edited", async () => {
    stubFetch();

    renderEditor({ editable: true });

    await waitFor(() => expect(instances[0].importXML).toHaveBeenCalled());
    expect(instances[0].kind).toBe("modeler");
  });

  it("hands over the means to serialize what is drawn, but only when editing", async () => {
    stubFetch();
    const onReady: SerializerHandler = vi.fn();

    const { unmount } = renderEditor({ editable: true, onReady });

    await waitFor(() => expect(onReady).toHaveBeenCalledWith(expect.any(Function)));
    const serialize = latestSerializer(onReady);
    await expect(serialize()).resolves.toBe("<saved/>");

    // And takes it back when the canvas goes away, so a page cannot save into a destroyed one
    unmount();
    expect(onReady).toHaveBeenLastCalledWith(null);
  });

  it("hands over nothing to serialize in view mode", async () => {
    stubFetch();
    const onReady: SerializerHandler = vi.fn();

    renderEditor({ onReady });

    await waitFor(() => expect(instances[0].importXML).toHaveBeenCalled());
    expect(onReady.mock.calls.every(call => call[0] === null)).toBe(true);
  });

  it("reports a diagram that could not be serialized rather than saving nothing", async () => {
    stubFetch();
    const onReady: SerializerHandler = vi.fn();
    renderEditor({ editable: true, onReady });
    await waitFor(() => expect(onReady).toHaveBeenCalledWith(expect.any(Function)));
    const serialize = latestSerializer(onReady);
    instances[0].saveXML = vi.fn().mockResolvedValue({ xml: undefined });

    await expect(serialize()).rejects.toThrow("could not be serialized");
  });

  it("says the diagram has unsaved changes as soon as one is made", async () => {
    stubFetch();
    const onDirtyChange = vi.fn();
    renderEditor({ editable: true, onDirtyChange });

    await waitFor(() => expect(instances[0].handlers.has("commandStack.changed")).toBe(true));
    expect(onDirtyChange).not.toHaveBeenCalled();

    instances[0].handlers.get("commandStack.changed")?.({});

    expect(onDirtyChange).toHaveBeenCalledWith(true);
  });

  it("does not watch for changes it could not make", async () => {
    stubFetch();
    const onDirtyChange = vi.fn();

    renderEditor({ onDirtyChange });

    await waitFor(() => expect(instances[0].importXML).toHaveBeenCalled());
    expect(instances[0].handlers.has("commandStack.changed")).toBe(false);
  });

  it("opens an empty canvas for a version with no diagram saved yet", async () => {
    // A brand new version has nothing stored under bpmn.xml; that is a canvas to start drawing on,
    // not a failure to report
    stubFetch(404);

    renderEditor({ editable: true });

    await waitFor(() => expect(instances[0].importXML).toHaveBeenCalledWith(EMPTY_BPMN));
    expect(screen.getByText("Please select an element.")).toBeInTheDocument();
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
  });

  it("shows the version it was moved to rather than the one before it", async () => {
    // The canvas is reused when only the path changes, so a version with nothing saved would otherwise go
    // on showing the previous version's diagram — and in edit mode a save would then write it there
    stubFetch();
    const { rerender } = renderEditor();
    await waitFor(() => expect(instances[0].importXML).toHaveBeenCalledWith(DIAGRAM));

    stubFetch(404);
    rerender(
      <ThemeProvider theme={appTheme} defaultMode="light">
        <BpmnEditor versionPath="/Workflows/timeOff/2-0" />
      </ThemeProvider>
    );

    await waitFor(() => expect(instances[0].importXML).toHaveBeenLastCalledWith(EMPTY_BPMN));
    // The same canvas throughout: what changed is the diagram in it, not the instance
    expect(instances).toHaveLength(1);
  });

  it("keeps the canvas out of sight until it holds the version asked for", async () => {
    // Until the load settles the canvas holds nothing, or the version looked at before this one, so it is
    // covered rather than presented empty with a spinner floating above it
    const { settle } = deferredFetch();
    const { container } = renderEditor();

    expect(await screen.findByRole("progressbar")).toBeInTheDocument();
    expect(container.querySelector("[aria-hidden=\"true\"]")).toBeInTheDocument();

    settle(true);

    await waitFor(() => expect(instances[0].importXML).toHaveBeenCalledWith(DIAGRAM));
    expect(screen.queryByRole("progressbar")).not.toBeInTheDocument();
    expect(container.querySelector("[aria-hidden=\"true\"]")).not.toBeInTheDocument();
  });

  it("reports a diagram the server refused to hand over", async () => {
    stubFetch(500);

    renderEditor();

    const report = await screen.findByRole("alert");
    expect(report).toHaveTextContent("(HTTP 500)");
  });

  it("reports a diagram bpmn-js could not import", async () => {
    // An unreadable response and an unparseable diagram are both "this cannot be displayed", and
    // bpmn-js has already worded this one
    stubFetch();
    refuseImports(new Error("unparsable XML"));

    renderEditor();

    expect(await screen.findByText("unparsable XML")).toBeInTheDocument();
    // Reported instead of waited on: the load settled, it just produced nothing that can be shown. That the
    // canvas is covered while this alert stands is the same showDiagram check the loading test pins down.
    expect(screen.queryByRole("progressbar")).not.toBeInTheDocument();
  });

  it("destroys the canvas it created when it goes away", async () => {
    stubFetch();
    const { unmount } = renderEditor();
    await waitFor(() => expect(instances).toHaveLength(1));

    unmount();

    expect(instances[0].destroy).toHaveBeenCalled();
  });

  it("stops caring about the diagram it asked for once it is gone", async () => {
    // The fetch outliving the canvas is the ordinary case when a version closes mid-load: the request
    // is not called off, but the component it would report to is gone, so nothing is recorded or shown.
    const { settle } = deferredFetch();
    const { unmount } = renderEditor();
    await waitFor(() => expect(instances).toHaveLength(1));

    unmount();
    settle(true);

    await waitFor(() => expect(instances[0].importXML).toHaveBeenCalledWith(DIAGRAM));
    expect(screen.queryByRole("progressbar")).not.toBeInTheDocument();
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
  });

  it("stops caring about a diagram it could not read once it is gone", async () => {
    // The other half of the same race: there is no longer anywhere to show the problem
    const { settle } = deferredFetch();
    const { unmount } = renderEditor();
    await waitFor(() => expect(instances).toHaveLength(1));

    unmount();
    settle(false);

    await waitFor(() => expect(screen.queryByRole("alert")).not.toBeInTheDocument());
  });
});
