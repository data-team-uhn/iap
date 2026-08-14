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
import { act, fireEvent, render, screen } from "@testing-library/react";

import { appTheme } from "@iap/frontend-commons/appTheme";
import NoticeSnackbar, { type Notice } from "@iap/frontend-commons/components/NoticeSnackbar";

const renderSnackbar = ({ notice, onClose = vi.fn() }: { notice?: Notice; onClose?: () => void }) => {
  render(
    <ThemeProvider theme={appTheme} defaultMode="light">
      <NoticeSnackbar notice={notice} onClose={onClose} />
    </ThemeProvider>
  );
  return { onClose };
};

const failure: Notice = {
  title: "Paper submissions could not be moved",
  message: "You do not have permission to do this. (HTTP 403)",
};

describe("NoticeSnackbar", () => {
  it("shows nothing at all until there is something to say", () => {
    renderSnackbar({});

    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
  });

  it("says what did not happen, and why", () => {
    renderSnackbar({ notice: failure });

    const notice = screen.getByRole("alert");
    expect(notice).toHaveTextContent("Paper submissions could not be moved");
    expect(notice).toHaveTextContent("You do not have permission to do this. (HTTP 403)");
  });

  it("can be dismissed through a button that says so", () => {
    const { onClose } = renderSnackbar({ notice: failure });

    fireEvent.click(screen.getByRole("button", { name: "Dismiss" }));

    expect(onClose).toHaveBeenCalled();
  });

  it("offers no retry when there is nothing sensible to retry", () => {
    renderSnackbar({ notice: failure });

    expect(screen.queryByRole("button", { name: "Retry" })).not.toBeInTheDocument();
  });

  it("gets out of the way when its retry is taken up, so a second failure can report itself", () => {
    const onRetry = vi.fn();
    const { onClose } = renderSnackbar({ notice: { ...failure, onRetry } });

    fireEvent.click(screen.getByRole("button", { name: "Retry" }));

    expect(onClose).toHaveBeenCalled();
    expect(onRetry).toHaveBeenCalled();
  });

  // How long a notice stays. Waiting is done with fake timers, so the suite does not.
  const waitOut = (milliseconds: number) => {
    act(() => { vi.advanceTimersByTime(milliseconds); });
  };

  afterEach(() => vi.useRealTimers());

  it("keeps a failure up: it carries something to read, and often something to click", () => {
    vi.useFakeTimers();
    const { onClose } = renderSnackbar({ notice: { ...failure, onRetry: vi.fn() } });

    waitOut(60000);

    expect(onClose).not.toHaveBeenCalled();
    expect(screen.getByRole("alert")).toBeInTheDocument();
  });

  it("keeps a warning up too", () => {
    vi.useFakeTimers();
    const { onClose } = renderSnackbar({ notice: { ...failure, severity: "warning" } });

    waitOut(60000);

    expect(onClose).not.toHaveBeenCalled();
  });

  it("lets the cheerful ones fade", () => {
    vi.useFakeTimers();
    const { onClose } = renderSnackbar({ notice: { title: "Category retired", severity: "success" } });

    waitOut(4000);

    expect(onClose).toHaveBeenCalled();
  });
});
