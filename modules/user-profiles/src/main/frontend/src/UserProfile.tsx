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

import { useCallback, useEffect, useMemo, useState } from "react";

import {
  Alert,
  Avatar,
  Box,
  Button,
  Chip,
  Divider,
  Paper,
  Stack,
  Typography,
} from "@mui/material";

import LoadingOverlay from "@iap/frontend-commons/components/LoadingOverlay";
import { useAuthenticatedFetch } from "@iap/frontend-commons/reLogin";

import { currentValue, readProfile, saveProfile, type Profile, type ProfileField } from "./profileApi";
import ProfileFieldControl from "./ProfileFieldControl";

// One or two initials identifying the person, from their full name where the profile carries one.
// Deliberately the same shape as the app bar's avatar, so the two read as the same person.
const initialsOf = (name: string): string => {
  const words = name.trim().split(/\s+/).filter(Boolean);
  if (words.length === 0) {
    return "";
  }
  return (words[0][0] + (words.length > 1 ? words[words.length - 1][0] : "")).toUpperCase();
};

// The values as they are recorded, which is what "changed" is measured against.
const recordedValues = (fields: ProfileField[]): Record<string, string> =>
  Object.fromEntries(fields.map(field => [ field.name, currentValue(field) ]));

// The person's own profile: what this instance records about them, and the settings they control.
//
// Everything on the page comes from the field catalogue rather than from anything hardcoded here —
// which fields exist, what they mean, what values they accept, and who may change them are all
// answered by the API in one document, so a deployment that adds a field gets a control for it and
// one that removes a field stops showing it.
//
// The two groups are the catalogue's own distinction: `profile` fields describe the person,
// `preference` fields say how they want the application to behave. Registered as a view on the
// `iap/coreUI/view` extension point at /profile.
function UserProfile() {
  const authenticatedFetch = useAuthenticatedFetch();

  const [ profile, setProfile ] = useState<Profile | null>(null);
  const [ draft, setDraft ] = useState<Record<string, string>>({});
  const [ loadError, setLoadError ] = useState("");
  const [ saving, setSaving ] = useState(false);
  const [ saved, setSaved ] = useState(false);
  const [ refused, setRefused ] = useState<Record<string, string>>({});
  const [ forbidden, setForbidden ] = useState("");

  // Chained rather than awaited so that nothing is set while the effect below is still running:
  // state settled synchronously inside an effect cascades renders, and the linter rightly refuses it.
  const load = useCallback(() =>
    readProfile(authenticatedFetch)
      .then(loaded => {
        setProfile(loaded);
        setDraft(recordedValues(loaded.fields));
        setLoadError("");
      })
      .catch((err: unknown) => {
        setLoadError(err instanceof Error ? err.message : "Could not load your profile.");
      }),
  [ authenticatedFetch ]);

  useEffect(() => {
    void load();
  }, [ load ]);

  // Only what the person actually changed is sent. Sending a field back unchanged would be
  // harmless for most of them, but a field they may not write would be refused and would turn every
  // save on the page into a refusal about something they never touched.
  const changes = useMemo(() => {
    if (!profile) {
      return {};
    }
    const recorded = recordedValues(profile.fields);
    return Object.fromEntries(
      profile.fields
        .filter(field => field.editable && draft[field.name] !== recorded[field.name])
        .map(field => [ field.name, draft[field.name] ]),
    );
  }, [ profile, draft ]);

  const dirty = Object.keys(changes).length > 0;

  const edit = (name: string, value: string) => {
    setDraft(current => ({ ...current, [name]: value }));
    // Last save's verdict is about last save's values; keeping it against a control the person has
    // since edited would be reporting a problem with something they already addressed
    setRefused(current => name in current ? Object.fromEntries(
      Object.entries(current).filter(([ field ]) => field !== name),
    ) : current);
    setSaved(false);
  };

  const submit = async () => {
    setSaving(true);
    setForbidden("");
    try {
      const outcome = await saveProfile(authenticatedFetch, changes);
      setRefused(outcome.refused);
      if (outcome.forbidden) {
        setForbidden("You are not allowed to change this profile.");
      }
      if (outcome.status === "success") {
        setSaved(true);
        // Re-read rather than assume: a value can be stored differently from how it was typed, and
        // the provenance of a field changes the moment it is first written
        await load();
      }
    } catch (err: unknown) {
      setForbidden(err instanceof Error ? err.message : "Could not save your profile.");
    } finally {
      setSaving(false);
    }
  };

  const discard = () => {
    if (profile) {
      setDraft(recordedValues(profile.fields));
    }
    setRefused({});
    setForbidden("");
    setSaved(false);
  };

  if (loadError) {
    return <Alert severity="error" sx={{ m: 3 }} action={<Button onClick={() => void load()}>Retry</Button>}>{loadError}</Alert>;
  }
  if (!profile) {
    return <LoadingOverlay open message="Loading your profile" />;
  }

  const group = (kind: ProfileField["kind"]) => profile.fields.filter(field => field.kind === kind);
  const fullName = currentValue(profile.fields.find(field => field.name === "fullName") ?? {} as ProfileField);

  return (
    <Box sx={{ maxWidth: 720, mx: "auto", p: { xs: 2, sm: 3 }, display: "flex", flexDirection: "column", gap: 3 }}>
      <Stack direction="row" spacing={2} sx={{ alignItems: "center" }}>
        <Avatar sx={{ width: 56, height: 56, bgcolor: "primary.main" }}>
          {initialsOf(fullName || profile.account)}
        </Avatar>
        <Box sx={{ minWidth: 0 }}>
          <Typography variant="h5" component="h1" noWrap>{fullName || profile.account}</Typography>
          <Stack direction="row" spacing={1} sx={{ alignItems: "center", mt: 0.5 }}>
            <Typography variant="body2" color="text.secondary">{profile.account}</Typography>
            {profile.external && (
              <Chip size="small" variant="outlined" label={`Signed in via ${profile.idp}`} />
            )}
          </Stack>
        </Box>
      </Stack>

      {forbidden && <Alert severity="error" onClose={() => setForbidden("")}>{forbidden}</Alert>}
      {saved && <Alert severity="success" onClose={() => setSaved(false)}>Your profile has been saved.</Alert>}
      {Object.keys(refused).length > 0 && (
        <Alert severity="warning">Nothing was saved. Please correct the fields marked below.</Alert>
      )}

      <Section
        title="About you"
        subtitle="What this platform records about you, and what other people here can see."
        fields={group("profile")}
        profile={profile}
        draft={draft}
        refused={refused}
        onChange={edit}
      />

      <Section
        title="Settings"
        subtitle="How you want this platform to behave."
        fields={group("preference")}
        profile={profile}
        draft={draft}
        refused={refused}
        onChange={edit}
      />

      { /* Kept with the form rather than pinned to the viewport: the page is short enough that a
           floating bar would cover content it is about, and the buttons stay reachable either way. */ }
      <Stack direction="row" spacing={1} sx={{ justifyContent: "flex-end" }}>
        <Button onClick={discard} disabled={!dirty || saving}>Discard changes</Button>
        <Button variant="contained" onClick={() => void submit()} disabled={!dirty || saving}>
          {saving ? "Saving…" : "Save changes"}
        </Button>
      </Stack>
    </Box>
  );
}

interface SectionProps {
  title: string;
  subtitle: string;
  fields: ProfileField[];
  profile: Profile;
  draft: Record<string, string>;
  refused: Record<string, string>;
  onChange: (name: string, value: string) => void;
}

// One titled group of fields. A group the catalogue has nothing in renders nothing at all, rather
// than an empty card promising something the instance does not record.
function Section({ title, subtitle, fields, profile, draft, refused, onChange }: SectionProps) {
  if (fields.length === 0) {
    return null;
  }
  return (
    <Paper variant="outlined" sx={{ p: { xs: 2, sm: 3 } }}>
      <Typography variant="h6" component="h2">{title}</Typography>
      <Typography variant="body2" color="text.secondary">{subtitle}</Typography>
      <Divider sx={{ my: 2 }} />
      <Stack spacing={3}>
        {fields.map(field => (
          <ProfileFieldControl
            key={field.name}
            field={field}
            profile={profile}
            value={draft[field.name] ?? ""}
            error={refused[field.name]}
            onChange={value => onChange(field.name, value)}
          />
        ))}
      </Stack>
    </Paper>
  );
}

export default UserProfile;
