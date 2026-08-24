#!/usr/bin/env python3
# -*- coding: utf-8 -*-

# Copyright 2026 DATA @ UHN. See the NOTICE file
# distributed with this work for additional information
# regarding copyright ownership.
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

"""An SMTPS server that files messages away instead of delivering them.

IAP sends mail over SMTPS -- TLS from the first byte, not STARTTLS -- because that is the protocol
Sling's mail service speaks, which rules out the usual off-the-shelf mail catchers. This accepts
that, writes every message it is given to a directory as an `.eml` file, and says yes to
everything else: it exists so a developer can read what IAP tried to send.

Accepting anything is the point, and the reason this must never be exposed outside a development
machine. It is an open relay that relays nothing.
"""

import asyncio
import datetime
import os
import ssl
import sys
from pathlib import Path

CERTIFICATE = os.environ.get('MAIL_CERT', '/certs/mail.crt')
PRIVATE_KEY = os.environ.get('MAIL_KEY', '/certs/mail.key')
MAIL_DIR = Path(os.environ.get('MAIL_DIR', '/mail'))
PORT = int(os.environ.get('MAIL_PORT', '465'))
HOSTNAME = os.environ.get('MAIL_HOSTNAME', 'smtps_test_container')

# A message larger than this is refused rather than buffered, so that a runaway sender cannot
# exhaust the container's memory. Generous enough for anything IAP composes.
MAX_MESSAGE_BYTES = 32 * 1024 * 1024


class Session:
    """One client connection, from the greeting to QUIT."""

    def __init__(self, reader, writer):
        self.reader = reader
        self.writer = writer
        self.sender = None
        self.recipients = []

    async def reply(self, line):
        self.writer.write((line + "\r\n").encode('utf-8'))
        await self.writer.drain()

    async def read_line(self):
        line = await self.reader.readline()
        return line.decode('utf-8', 'replace').rstrip("\r\n") if line else None

    async def run(self):
        await self.reply("220 {} IAP mail catcher".format(HOSTNAME))
        while True:
            command = await self.read_line()
            if command is None:
                break
            verb = command.split(' ')[0].upper()
            if verb in ('EHLO', 'HELO'):
                await self.greet(verb, command)
            elif verb == 'AUTH':
                # Sling passes a username and password whether or not one is needed, so the
                # exchange has to be played out; what it says is never checked.
                await self.authenticate(command)
            elif verb == 'MAIL':
                self.sender = address_in(command)
                self.recipients = []
                await self.reply("250 OK")
            elif verb == 'RCPT':
                self.recipients.append(address_in(command))
                await self.reply("250 OK")
            elif verb == 'DATA':
                await self.receive_message()
            elif verb == 'RSET':
                self.sender = None
                self.recipients = []
                await self.reply("250 OK")
            elif verb in ('NOOP', 'HELP'):
                await self.reply("250 OK")
            elif verb == 'QUIT':
                await self.reply("221 Bye")
                break
            else:
                await self.reply("502 Command not implemented")

    async def greet(self, verb, command):
        name = command.partition(' ')[2].strip() or 'client'
        if verb == 'HELO':
            await self.reply("250 {}".format(HOSTNAME))
            return
        # No STARTTLS is advertised: the connection has been encrypted since it was opened.
        await self.reply("250-{} greets {}".format(HOSTNAME, name))
        await self.reply("250-8BITMIME")
        await self.reply("250-SMTPUTF8")
        await self.reply("250-SIZE {}".format(MAX_MESSAGE_BYTES))
        await self.reply("250 AUTH PLAIN LOGIN")

    async def authenticate(self, command):
        mechanism = (command.split(' ') + [''])[1].upper()
        if mechanism == 'PLAIN':
            # The credentials may ride along with the command, or follow a bare challenge.
            if len(command.split(' ')) < 3:
                await self.reply("334 ")
                if await self.read_line() is None:
                    return
        elif mechanism == 'LOGIN':
            await self.reply("334 VXNlcm5hbWU6")     # "Username:"
            if await self.read_line() is None:
                return
            await self.reply("334 UGFzc3dvcmQ6")     # "Password:"
            if await self.read_line() is None:
                return
        else:
            await self.reply("504 Unrecognized authentication type")
            return
        await self.reply("235 Authentication successful")

    async def receive_message(self):
        if not self.recipients:
            await self.reply("503 No recipients")
            return
        await self.reply("354 End data with <CR><LF>.<CR><LF>")

        chunks = []
        size = 0
        too_large = False
        while True:
            line = await self.reader.readline()
            if not line:
                return
            if line.rstrip(b"\r\n") == b".":
                break
            # Dot-stuffing: a line the sender began with a period arrives with two.
            if line.startswith(b".."):
                line = line[1:]
            size += len(line)
            if size > MAX_MESSAGE_BYTES:
                too_large = True
                continue
            chunks.append(line)

        if too_large:
            await self.reply("552 Message exceeds {} bytes".format(MAX_MESSAGE_BYTES))
            return

        path = store(b"".join(chunks), self.recipients)
        print("Received a message from {} for {} -> {}".format(
            self.sender or '<>', ', '.join(self.recipients), path.name), flush=True)
        await self.reply("250 OK: stored as {}".format(path.name))


def address_in(command):
    """Pull the address out of `MAIL FROM:<someone@example.org> SIZE=42`."""
    _, _, rest = command.partition(':')
    address = rest.strip().split(' ')[0]
    return address.strip('<>')


def store(message, recipients):
    MAIL_DIR.mkdir(parents=True, exist_ok=True)
    stamp = datetime.datetime.now().strftime('%Y%m%d-%H%M%S-%f')
    recipient = ''.join(c if c.isalnum() or c in '.@-_' else '_'
                        for c in (recipients[0] if recipients else 'unknown'))
    path = MAIL_DIR / "{}-{}.eml".format(stamp, recipient)
    path.write_bytes(message)
    # The directory belongs to whoever generated the Compose file; this container runs as root, so
    # the files it drops there are readable by everyone rather than only by root.
    path.chmod(0o644)
    return path


async def serve():
    context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    try:
        context.load_cert_chain(CERTIFICATE, PRIVATE_KEY)
    except OSError as error:
        sys.exit("Cannot read the certificate ({}) or key ({}): {}".format(
            CERTIFICATE, PRIVATE_KEY, error))

    async def handle(reader, writer):
        try:
            await Session(reader, writer).run()
        except (ConnectionResetError, ssl.SSLError, asyncio.IncompleteReadError) as error:
            # A client hanging up mid-conversation is not worth a stack trace.
            print("Connection ended: {}".format(error), flush=True)
        finally:
            writer.close()

    server = await asyncio.start_server(handle, '0.0.0.0', PORT, ssl=context)
    print("Listening for SMTPS on port {}, writing messages to {}".format(PORT, MAIL_DIR),
          flush=True)
    async with server:
        await server.serve_forever()


if __name__ == '__main__':
    try:
        asyncio.run(serve())
    except KeyboardInterrupt:
        pass
