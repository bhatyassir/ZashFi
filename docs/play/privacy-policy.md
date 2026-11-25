# ZashFi privacy policy (draft)

_Draft for the owner to review, complete and host at a public address. Fill in the two
placeholders. Not legal advice._

**Effective date:** `<date>`
**Contact:** `<owner's contact email>`

ZashFi is an app for managing your own OpenWrt router from your phone.

## What the app does not do

- It has no account and no server. The developer receives no data from the app.
- It contains no advertising, analytics or tracking code.
- It does not request access to your location, contacts, camera or general file storage.

## What stays between your phone and your router

To work, the app connects directly to the router you add, on your home network or your VPN.
It only connects to private network addresses.

- **Router and AdGuard Home passwords.** Sent only to your router or your AdGuard Home to sign
  in. If you choose "Remember", the password is stored on your phone, encrypted with a key kept
  in the phone's secure hardware that opens with your fingerprint or PIN. If you turn on alerts,
  a second encrypted copy is kept that opens without your fingerprint, so checks can run in the
  background; turning alerts off deletes it.
- **Router details.** The router's name, address, VPN address and certificate fingerprint are
  stored on your phone so the app can reconnect and check it is talking to the right router.
- **Your network.** Device names and addresses, internet status and settings are read from your
  router and shown on screen. With alerts on, the app keeps a list of devices it has seen, on
  your phone, to tell you when a new one joins. The home-screen widget keeps only counts and
  on/off states.
- **DNS query logs.** ZashFi reads logs from your AdGuard Home instance. When you export them,
  ZashFi writes the selected queries to a temporary CSV or text file in its private cache.
  Android's share menu lets you choose the app that receives the file.

## Backups

The app's data is excluded from Android cloud backups and phone-to-phone transfers.

## Deleting your data

"Forget this router" in the app deletes that router's details and saved passwords.
Uninstalling the app deletes everything it stored.

## Changes

Changes to this policy will be posted at this address with a new effective date.
