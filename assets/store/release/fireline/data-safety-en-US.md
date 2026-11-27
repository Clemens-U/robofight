# Opcode FIRELINE — Google Play Data safety draft (en-US)

Source of truth checked against `fireline-app` version `0.1` at `bd824e4`.
Re-check this declaration whenever permissions, dependencies, storage, import,
export or telemetry behavior changes.

## Overview answers

- Does the app collect or share any of the required user data types? **No**
- Is all collected user data encrypted in transit? **Not applicable — the app
  does not collect or transmit user data**
- Do users have a way to request that data be deleted? **Not applicable — the
  developer holds no account or server-side app data**
- Does the app provide an account-creation method? **No**

## Basis for the answers

- The manifest declares no permissions, including no Internet permission.
- There are no advertising, analytics, tracking or third-party collection SDKs.
- Firmware source, file names, selected firmware, mission selection and run
  records are processed and stored locally in the app-private database and
  preferences.
- Firmware import and export use Android's system document picker. The user
  explicitly chooses the source or destination; FIRELINE does not upload files
  to the developer.
- Android may back up app-private data according to the user's device, account
  and backup settings. That system service is governed by the selected backup
  provider, not by FIRELINE or its developer.

## User-facing deletion statement

Users can delete individual firmware files inside FIRELINE. Clearing app
storage or uninstalling the app deletes its local app data from the device. A
device backup may retain or later restore a copy according to the backup
provider's settings and retention policy.

## Play Console notes

Answer the questionnaire from the behavior of the uploaded production artifact,
not this draft alone. In particular, inspect the final merged manifest and SDK
dependency report before submission. The privacy-policy URL is:

`https://opcode-fireline.appsystems.at/privacy/`
