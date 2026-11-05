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

import { useState, type SyntheticEvent } from 'react';

import { Alert, Button, Stack, TextField } from '@mui/material';

// Sling's form authentication endpoint. With `j_validate`, it answers 200 or 403 instead of
// redirecting, and the page stays where it is.
const LOGIN_URL = "/j_security_check";

interface LoginFormProps {
  // Called once Sling has accepted the credentials and the session exists
  onSuccess: () => void;
}

// A username and password form, checked by Sling's form authentication. It does not leave the
// page: on success it calls `onSuccess`, and on a refusal it says so and can be submitted again.
export default function LoginForm({ onSuccess }: LoginFormProps) {
  const [username, setUsername] = useState("");
  const [password, setPassword] = useState("");
  const [failed, setFailed] = useState(false);
  const [busy, setBusy] = useState(false);

  const submit = (event: SyntheticEvent<HTMLFormElement, SubmitEvent>) => {
    event.preventDefault();
    setBusy(true);
    fetch(LOGIN_URL, {
      method: "POST",
      headers: { "Content-Type": "application/x-www-form-urlencoded" },
      body: new URLSearchParams({ "j_username": username, "j_password": password, "j_validate": "true" }),
    })
      .then((response) => {
        if (!response.ok) {
          throw new Error(response.statusText);
        }
        setFailed(false);
        // The form can stay on screen after a success and be asked to sign in again, so it must
        // not stay disabled
        setBusy(false);
        onSuccess();
      })
      .catch(() => {
        setFailed(true);
        setBusy(false);
      });
  };

  return (
    <Stack component="form" onSubmit={submit} spacing={2}>
      {failed && <Alert severity="error">Invalid username or password</Alert>}
      <TextField
        label="Username"
        name="j_username"
        autoComplete="username"
        required
        value={username}
        onChange={(event) => setUsername(event.target.value)}
      />
      <TextField
        label="Password"
        name="j_password"
        type="password"
        autoComplete="current-password"
        required
        value={password}
        onChange={(event) => setPassword(event.target.value)}
      />
      <Button type="submit" variant="contained" color="primary" disabled={busy || !username || !password}>
        Sign in
      </Button>
    </Stack>
  );
}
