# Security Policy

## Overview

**PureTune (Pulse Music)** is a 100% offline Android music player designed with strict privacy and least-privilege security principles:

- **Zero Network Access:** The application does not request `android.permission.INTERNET` or `android.permission.ACCESS_NETWORK_STATE`. No user data, audio metadata, or telemetry ever leaves the device.
- **No Analytics or Trackers:** The application includes zero third-party analytics, crash reporting, or advertising SDKs.
- **Read-Only Media Access:** Audio files are queried strictly in read-only mode via Android's `MediaStore` and `ContentResolver` APIs (`READ_MEDIA_AUDIO` on Android 13+, `READ_EXTERNAL_STORAGE` capped at `maxSdkVersion="32"` on Android 12 and lower).
- **Hardened External Intent Handling:** Incoming `ACTION_VIEW` and `ACTION_OPEN_DOCUMENT` intents are validated against path traversal into private application directories and restricted to read-only URI permission grants (`FLAG_GRANT_READ_URI_PERMISSION`).

## Supported Versions

Security updates are applied to the latest `main` branch release:

| Version | Supported |
| ------- | --------- |
| `1.0.x` (latest `main`) | :white_check_mark: |
| `< 1.0` | :x: |

## Reporting a Vulnerability

If you discover a security vulnerability in PureTune, please report it responsibly:

1. **Use GitHub Private Vulnerability Reporting:**
   - Navigate to the **Security** tab of this repository on GitHub.
   - Click **Report a vulnerability** to open a private security advisory with the maintainer.
2. **Do Not Open a Public Issue:**
   - Please avoid posting exploitable details in public GitHub Issues or Discussions until a fix has been released.
3. **Information to Include:**
   - A clear description of the issue and potential impact.
   - Steps to reproduce (e.g., crafted Intent/URI, Android API level, device configuration).
   - Any suggested remediation or patch if available.

We aim to acknowledge vulnerability reports within **72 hours** and provide a remediation timeline or fix as quickly as possible.
