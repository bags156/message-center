# Publishing an Obtainium update

## Permanent identity

- Application ID: `io.github.bags156.messagecenter`
- Public signing fingerprint: [`../signing-certificate.sha256`](../signing-certificate.sha256)
- Private signing material: GitHub Actions repository secret `ANDROID_SIGNING_KEY`

The secret is JSON containing `privateKey` (base64 PKCS#8 DER RSA key) and `certificate` (base64 X.509 DER certificate). It is never committed, written to logs, or uploaded as a build artifact. A release refuses to proceed if the secret is missing, the signing fingerprint changes, the package changes, or the version code fails to increase over the previous release.

Keep a private backup of the signing material. A different key cannot update installations signed with the original key through the normal APK update path. Repository Actions secrets can be replaced but cannot be read back through Settings.

## Release steps

1. Make the app changes.
2. Increase **both** `versionName` and `versionCode` in `version.properties`.
3. Update `docs/release-notes.md` with the actual changes and any remaining limits.
4. Commit to `main`, and check that Android build and tests pass.
5. Open **Actions → Android build and release → Run workflow**. Choose `main` and leave **Publish a signed APK release for Obtainium** checked.
6. The workflow builds/tests the app, signs the APK, verifies package/version/signature, and creates a GitHub Release with `message-center.apk`, checksum, public signing fingerprint, and version metadata.
7. Check for updates in Obtainium. The repository URL stays the same.

The first release is an alpha for device testing, even though it is on the normal release feed so Obtainium can find it without extra filters. Do not claim that compiled code or unit tests verify live carrier RCS behavior.

## Release troubleshooting

- **Missing signing key:** configure `ANDROID_SIGNING_KEY` as an Actions repository secret.
- **Version code did not increase:** update `version.properties` and commit before rerunning.
- **Certificate mismatch:** restore the original private signing material; do not substitute a new key.
- **Quick reply unavailable:** Google Messages may have removed the notification action. Open that conversation in Google Messages.
