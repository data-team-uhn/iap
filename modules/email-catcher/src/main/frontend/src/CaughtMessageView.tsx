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

import { type ReactNode, useCallback, useEffect, useRef, useState } from "react";

import { Box, Divider, Stack, Tab, Tabs, Typography } from "@mui/material";
import { useLocation } from "react-router";

import AdminScreen from "@iap/admin-console/AdminScreen";
import LoadError from "@iap/frontend-commons/components/LoadError";
import LoadingOverlay from "@iap/frontend-commons/components/LoadingOverlay";
import { useAuthenticatedFetch } from "@iap/frontend-commons/reLogin";

import { type CaughtMessage, fetchCaughtMessage, messageNameFromRoute } from "./caughtMailApi";

/** A timestamp as a reader wants it, or the raw value if it is not one we can parse. */
function moment(value: string | undefined): string {
  if (value === undefined) {
    return "—";
  }
  const parsed = new Date(value);
  return Number.isNaN(parsed.getTime()) ? value : parsed.toLocaleString();
}

// One labelled fact about the message. Addresses are kept one per line rather than joined: a comma
// is a legal character inside a display name, so a joined list is ambiguous exactly where somebody
// is checking whether the addressing came out right.
function Fact({ label, children }: { label: string; children: ReactNode }) {
  return (
    <Box>
      <Typography variant="caption" color="text.secondary" component="div">{label}</Typography>
      <Typography variant="body2" component="div" sx={{ wordBreak: "break-word" }}>{children}</Typography>
    </Box>
  );
}

// An address list, or nothing at all when the message carried none — an empty "Bcc" heading would
// suggest the message had a blind copy that failed to render.
function Addresses({ label, values }: { label: string; values: string[] }) {
  if (values.length === 0) {
    return null;
  }
  return (
    <Fact label={label}>
      {values.map(address => <Box key={address}>{address}</Box>)}
    </Fact>
  );
}

// Text shown exactly as it was stored: a plain text body, or the HTML behind the rendering. Scrolls
// inside its own box, so a long message cannot push the rest of the page out of reach.
function Preformatted({ text }: { text: string }) {
  return (
    <Box
      component="pre"
      sx={{
        m: 0,
        p: 1.5,
        overflowX: "auto",
        maxHeight: 480,
        overflowY: "auto",
        bgcolor: "action.hover",
        borderRadius: 1,
        fontSize: "0.8125rem",
        lineHeight: 1.5,
        whiteSpace: "pre-wrap",
        wordBreak: "break-word",
      }}
    >
      {text}
    </Box>
  );
}

/**
 * The HTML body as a recipient's mail client would draw it.
 *
 * <strong>In a sandbox with nothing allowed.</strong> These bodies come from the platform's own
 * templates today, but a caught message is whatever was handed to the mail service, and a viewer
 * that executes it would be running that inside an administrator's session. An empty `sandbox`
 * blocks scripts, forms, navigation and same-origin access at once, which costs this page nothing:
 * what is being checked is how the message reads, and a link in it is worth seeing rather than
 * following.
 */
function Rendered({ html }: { html: string }) {
  return (
    <Box
      component="iframe"
      title="The message as a recipient would see it"
      sandbox=""
      srcDoc={html}
      sx={{
        width: "100%",
        height: 480,
        border: 1,
        borderColor: "divider",
        borderRadius: 1,
        // Mail is written for a white background and mostly says so nowhere, so a dark theme would
        // otherwise show black text on a dark canvas
        bgcolor: "common.white",
      }}
    />
  );
}

interface BodyView {
  id: string;
  label: string;
  content: ReactNode;
}

// The ways this particular message can be read, in the order somebody wants them: as it would look,
// then as it was written. A message carries a plain text body, an HTML one, or both — and each way
// of reading it carries the body it draws, so that no rendering has to cope with a body that is not
// there.
function viewsOf(message: CaughtMessage): BodyView[] {
  const { htmlBody, textBody } = message;
  const views: BodyView[] = [];
  if (htmlBody !== undefined) {
    views.push({ id: "rendered", label: "Message", content: <Rendered html={htmlBody} /> });
  }
  if (textBody !== undefined) {
    views.push({ id: "text", label: "Plain text", content: <Preformatted text={textBody} /> });
  }
  if (htmlBody !== undefined) {
    views.push({ id: "source", label: "HTML source", content: <Preformatted text={htmlBody} /> });
  }
  return views;
}

/**
 * The administration console page for one caught message, at {@code /admin/mail/<name>}: who it was
 * addressed to, what it said, and the headers it carried.
 */
function CaughtMessageView() {
  const { pathname } = useLocation();
  const doFetch = useAuthenticatedFetch();
  // Derived at render, never stored: the React Compiler lint rejects a setState reached
  // synchronously from an effect, and a route that names no message is not a fetch to make
  const name = messageNameFromRoute(pathname);

  const [ message, setMessage ] = useState<CaughtMessage | null>(null);
  const [ loadError, setLoadError ] = useState<string | null>(null);
  const [ settled, setSettled ] = useState(false);
  const [ view, setView ] = useState("rendered");

  // Reads are sent in order but can land out of order — a retry can overtake the read it is
  // retrying — so each one carries a token and only the newest is applied
  const newestRead = useRef(0);

  // Both the first read and the retry button go through this, so a retry cannot drift from the load
  // it is retrying. It resolves only once the fetch has settled, which is what lets LoadError show
  // the attempt's own progress.
  const load = useCallback((): Promise<void> => {
    if (name === null) {
      return Promise.resolve();
    }
    newestRead.current += 1;
    const token = newestRead.current;
    // Written with callbacks rather than await deliberately: every setState below then sits in a
    // promise callback, which is what keeps react-hooks/set-state-in-effect satisfied when the
    // effect calls this
    return fetchCaughtMessage(doFetch, name)
      .then(result => ({ result, failure: null as string | null }))
      .catch((cause: unknown) => ({
        result: null,
        failure: cause instanceof Error ? cause.message : "The message could not be read",
      }))
      .then(({ result, failure }) => {
        if (token !== newestRead.current) {
          return;
        }
        setMessage(result);
        setLoadError(failure);
        setSettled(true);
      });
  }, [ doFetch, name ]);

  // Navigating from one message to another must not leave the previous one on screen under the new
  // one's heading. Done as a render-phase adjustment rather than in the effect, because a setState
  // reached synchronously from an effect is what react-hooks/set-state-in-effect rejects.
  const [ shown, setShown ] = useState(name);
  if (shown !== name) {
    setShown(name);
    setMessage(null);
    setLoadError(null);
    setSettled(false);
  }

  useEffect(() => {
    void load();
  }, [ load ]);

  if (name === null) {
    // Not a LoadError: nothing failed to load and retrying cannot help, because the address itself
    // names no single message
    return (
      <AdminScreen title="Caught message">
        <Typography color="text.secondary">
          This address does not name a caught message.
        </Typography>
      </AdminScreen>
    );
  }

  const views = message === null ? [] : viewsOf(message);
  // The chosen view is kept across messages, so that reading several in a row does not mean picking
  // "HTML source" again each time — but a message that has no such body falls back to what it does
  // have rather than showing an empty panel
  const shownView = views.find(candidate => candidate.id === view) ?? views.at(0);

  return (
    <AdminScreen title={message?.subject ?? "Caught message"}>
      <LoadingOverlay open={!settled} />
      {loadError !== null && (
        <LoadError
          title="The message could not be read"
          message={loadError}
          onRetry={load}
          sx={{ mb: 2 }}
        />
      )}
      {message !== null && (
        <Stack spacing={3}>
          <Box
            sx={{
              display: "grid",
              gap: 2,
              gridTemplateColumns: { xs: "1fr", sm: "repeat(3, 1fr)" },
            }}
          >
            <Addresses label="From" values={message.from} />
            <Addresses label="To" values={message.to} />
            <Addresses label="Cc" values={message.cc} />
            <Addresses label="Bcc" values={message.bcc} />
            <Addresses label="Reply to" values={message.replyTo} />
            <Fact label="Caught">{moment(message.caughtAt)}</Fact>
          </Box>

          <Divider />

          {shownView === undefined
            ? (
              <Typography variant="body2" color="text.secondary">
                This message has no body.
              </Typography>
            )
            : (
              <Box>
                {views.length > 1 && (
                  <Tabs
                    value={shownView.id}
                    onChange={(_event, chosen: string) => { setView(chosen); }}
                    sx={{ mb: 2 }}
                  >
                    {views.map(candidate => (
                      <Tab key={candidate.id} value={candidate.id} label={candidate.label} />
                    ))}
                  </Tabs>
                )}
                {shownView.content}
              </Box>
            )}

          {message.headers.length > 0 && (
            <Box>
              <Typography variant="subtitle2" sx={{ mb: 0.5 }}>Headers</Typography>
              <Typography variant="caption" color="text.secondary" component="div" sx={{ mb: 0.5 }}>
                Everything the message carried beyond the addresses and the subject above.
              </Typography>
              <Preformatted text={message.headers.join("\n")} />
            </Box>
          )}
        </Stack>
      )}
    </AdminScreen>
  );
}

export default CaughtMessageView;
