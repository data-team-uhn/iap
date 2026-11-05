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

import { type ReactNode, useState } from "react";

import { Box, Divider, Stack, Tab, Tabs, Typography } from "@mui/material";
import { useLocation } from "react-router";

import AdminScreen from "@iap/admin-console/AdminScreen";
import LoadError from "@iap/frontend-commons/components/LoadError";
import LoadingOverlay from "@iap/frontend-commons/components/LoadingOverlay";

import { type CaughtMessage, messageNameFromRoute } from "./caughtMailModel";
import { useCaughtMessage } from "./useCaughtMail";

/** A timestamp in the reader's locale; the raw value if it cannot be parsed, or a dash if absent. */
function moment(value: string | undefined): string {
  if (value === undefined) {
    return "—";
  }
  const parsed = new Date(value);
  return Number.isNaN(parsed.getTime()) ? value : parsed.toLocaleString();
}

// One labelled fact about the message.
function Fact({ label, children }: { label: string; children: ReactNode }) {
  return (
    <Box>
      <Typography variant="caption" component="div">{label}</Typography>
      <Typography variant="body2" component="div" sx={{ wordBreak: "break-word" }}>{children}</Typography>
    </Box>
  );
}

// An address list, one address per line, since a display name may contain a comma. Renders nothing
// if the list is empty.
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

// Text exactly as it was stored, such as a plain text body or the HTML source, in a box with its own
// scroll bars.
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
 * The HTML body as a mail client would draw it.
 *
 * The iframe has an empty `sandbox`, which blocks scripts, forms, navigation and same-origin access.
 * A caught message is whatever was handed to the mail service, and this page runs in an
 * administrator's session.
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
        // Mail is written for a white background, and often does not set one
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

// The views of a message's bodies: the rendered HTML, the plain text, and the HTML source. A view
// is listed only if the message has its body.
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
 * The administration console page for one caught message, at `/admin/mail/<name>`: its addresses,
 * its bodies and its other headers.
 */
function CaughtMessageView() {
  const { pathname } = useLocation();
  const name = messageNameFromRoute(pathname);

  const { message, loadError, settled, reload } = useCaughtMessage(name);
  const [ view, setView ] = useState("rendered");

  if (name === null) {
    // Not a LoadError, since nothing failed and a retry cannot help
    return (
      <AdminScreen title="Caught message">
        <Typography variant="placeholder">
          This address does not name a caught message.
        </Typography>
      </AdminScreen>
    );
  }

  const views = message === null ? [] : viewsOf(message);
  // The chosen view is kept from one message to the next. A message without that body shows its
  // first view instead.
  const shownView = views.find(candidate => candidate.id === view) ?? views.at(0);

  return (
    <AdminScreen title={message?.subject ?? "Caught message"}>
      <LoadingOverlay open={!settled} />
      {loadError !== null && (
        <LoadError
          title="The message could not be read"
          message={loadError}
          onRetry={reload}
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
              <Typography variant="placeholder">
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
              <Typography variant="caption" component="div" sx={{ mb: 0.5 }}>
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
