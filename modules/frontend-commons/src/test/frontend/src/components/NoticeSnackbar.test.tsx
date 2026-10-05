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
import { act, fireEvent, render, screen, within } from "@testing-library/react";

import { appTheme } from "@iap/frontend-commons/appTheme";
import { NoticeProvider, useNotice, type Notice } from "@iap/frontend-commons/components/NoticeSnackbar";

// A screen that raises whichever of its notices is asked for, the way a real one would after an action
function Raiser({ notices }: { notices: Notice[] }) {
  const raise = useNotice();
  return (
    <>
      { notices.map(notice => (
        <button key={notice.title} type="button" onClick={() => raise(notice)}>{`Raise ${notice.title}`}</button>
      )) }
    </>
  );
}

const renderPage = (...notices: Notice[]) => render(
  <ThemeProvider theme={appTheme} defaultMode="light">
    <NoticeProvider><Raiser notices={notices} /></NoticeProvider>
  </ThemeProvider>
);

const raise = (notice: Notice) => fireEvent.click(screen.getByRole("button", { name: `Raise ${notice.title}` }));

// What is on screen, oldest first, by title
const shown = () => screen.queryAllByRole("alert").map(alert => within(alert).getByText(/./, { selector: ".MuiAlertTitle-root" }).textContent);

const failure: Notice = {
  title: "Paper submissions could not be moved",
  message: "You do not have permission to do this. (HTTP 403)",
};

// How long a notice stays. Waiting is done with fake timers, so the suite does not.
const waitOut = (milliseconds: number) => {
  act(() => { vi.advanceTimersByTime(milliseconds); });
};

afterEach(() => vi.useRealTimers());

describe("NoticeProvider", () => {
  it("shows nothing at all until there is something to say", () => {
    renderPage(failure);

    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
  });

  it("says what did not happen, and why", () => {
    renderPage(failure);

    raise(failure);

    const notice = screen.getByRole("alert");
    expect(notice).toHaveTextContent("Paper submissions could not be moved");
    expect(notice).toHaveTextContent("You do not have permission to do this. (HTTP 403)");
  });

  it("can be dismissed through a button that says so", () => {
    renderPage(failure);
    raise(failure);

    fireEvent.click(screen.getByRole("button", { name: "Dismiss" }));

    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
  });

  it("offers no retry when there is nothing sensible to retry", () => {
    renderPage(failure);
    raise(failure);

    expect(screen.queryByRole("button", { name: "Retry" })).not.toBeInTheDocument();
  });

  it("gets out of the way when its retry is taken up, so a second failure can report itself", () => {
    const onRetry = vi.fn();
    const retriable = { ...failure, onRetry };
    renderPage(retriable);
    raise(retriable);

    fireEvent.click(screen.getByRole("button", { name: "Retry" }));

    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
    expect(onRetry).toHaveBeenCalled();
  });

  it("keeps a failure up: it carries something to read, and often something to click", () => {
    vi.useFakeTimers();
    const retriable = { ...failure, onRetry: vi.fn() };
    renderPage(retriable);
    raise(retriable);

    waitOut(60000);

    expect(screen.getByRole("alert")).toBeInTheDocument();
  });

  it("keeps a warning up too", () => {
    vi.useFakeTimers();
    const warning: Notice = { ...failure, severity: "warning" };
    renderPage(warning);
    raise(warning);

    waitOut(60000);

    expect(screen.getByRole("alert")).toBeInTheDocument();
  });

  it("lets the cheerful ones fade", () => {
    vi.useFakeTimers();
    const done: Notice = { title: "Category retired", severity: "success" };
    renderPage(done);
    raise(done);

    waitOut(4000);

    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
  });

  it("shows every notice raised, the newest nearest the edge", () => {
    const later: Notice = { title: "Paper submissions could not be archived" };
    renderPage(failure, later);

    raise(failure);
    raise(later);

    expect(shown()).toEqual([ failure.title, later.title ]);
  });

  it("shows the same notice once, however often it is raised", () => {
    const later: Notice = { title: "Paper submissions could not be archived" };
    renderPage(failure, later);

    raise(failure);
    raise(later);
    raise(failure);

    expect(shown()).toEqual([ later.title, failure.title ]);
  });

  it("lets the oldest give way once there is no more room", () => {
    const many = Array.from({ length: 6 }, (_, index): Notice => ({ title: `Submission ${index + 1} could not be moved` }));
    renderPage(...many);

    many.forEach(raise);

    expect(shown()).toEqual(many.slice(1).map(notice => notice.title));
  });

  it("fades a cheerful notice on its own clock, whatever else is raised meanwhile", () => {
    vi.useFakeTimers();
    const done: Notice = { title: "Category retired", severity: "success" };
    renderPage(done, failure);
    raise(done);

    waitOut(3000);
    raise(failure);
    waitOut(1000);

    expect(shown()).toEqual([ failure.title ]);
  });

  it("dismisses one notice and leaves the others", () => {
    const later: Notice = { title: "Paper submissions could not be archived" };
    renderPage(failure, later);
    raise(failure);
    raise(later);

    fireEvent.click(within(screen.getAllByRole("alert")[0]).getByRole("button", { name: "Dismiss" }));

    expect(shown()).toEqual([ later.title ]);
  });

  it("refuses a screen raising notices with no provider to show them", () => {
    // React reports the render error as well as throwing it
    const reported = vi.spyOn(console, "error").mockImplementation(() => undefined);

    expect(() => render(<Raiser notices={[ failure ]} />)).toThrow("needs a <NoticeProvider>");
    reported.mockRestore();
  });
});
