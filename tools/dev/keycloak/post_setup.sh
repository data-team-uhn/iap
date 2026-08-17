#!/bin/bash

curl -u admin:admin \   -F 'iap:defaultDisabled@TypeHint=Boolean' -F 'iap:defaultDisabled=false' \   http://localhost:8080/Extensions/SignInMethod/Keycloak
