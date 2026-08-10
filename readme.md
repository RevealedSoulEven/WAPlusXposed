# MetaPlusXposed

MetaPlusXposed (formerly WAPlusXposed) is a lightweight, efficient Xposed/LSPosed module that unlocks **Meta Plus** subscription benefits across the three flagship Meta apps — **WhatsApp, Instagram, and Facebook** — without a subscription. By utilizing dynamic APK analysis, the module hooks the entitlement and subscription verification routines without relying on hardcoded offsets, so it keeps working across app updates and re-obfuscation.

## Supported Apps

| App | Package | What is unlocked |
| --- | --- | --- |
| WhatsApp | `com.whatsapp` | Plus customization: exclusive themes, app launcher icons, premium sticker packs, custom ringtones, and more |
| Instagram | `com.instagram.android` | IG Plus benefits and the premium app-icon library (Throwback, Sketch, Ultragram, Together, Dazzle, …) |
| Facebook | `com.facebook.katana` | Plus benefits and custom app icons (vaporwave, bubbles, hearts, …) |

## What the module overcomes

Meta gates Plus features behind several independent client-side checks. The module defeats each one:

* **Benefit entitlement checks** — the `is_benefit_active`-style `(String) -> boolean` checks (e.g. Instagram's `X.7ij`, Facebook's `X.9Sb`) are forced to return `true`, unlocking every benefit at once.
* **Active-benefit sets** — UI listeners receive the active-benefit `Set` and can re-lock features; the module injects every benefit key so re-locking never happens.
* **Subscription status gates** — the IG Plus / master "Plus active" checks (e.g. Instagram's `X.Kj4`, WhatsApp's entitlement-provider field) are forced open, so no subscription or trial is required.
* **Per-icon server-side locks** — premium app icons arrive from the server pre-locked (`IG_PLUS_LOCKED`); the module rewrites each icon's state to available so any icon can be selected and applied.
* **Direct field reads** — some UIs read entitlement fields directly instead of calling methods; those fields are populated / forced after construction.

Because every class and method name is discovered at runtime by DexKit (never hardcoded), the module survives app updates automatically.

## Features

* Unlocks premium customization in WhatsApp, Instagram, and Facebook as listed above.
* **Optimized Execution:** Uses DexKit to dynamically scan the target applications combined with a robust caching manager to ensure fast initialization and compatibility across application updates.

---

## Requirements

* A rooted Android device or an environment supporting hook frameworks.
* **LSPosed Framework** (or a compatible Xposed implementation) installed and active.

---

## Installation

1.  Download and install the latest `MetaPlusXposed` APK from the [Releases](https://github.com/RevealedSoulEven/WAPlusXposed/releases) section.
2.  Open your **LSPosed** manager application.
3.  Navigate to the modules section, locate **MetaPlusXposed**, and toggle **Enable Module**.
4.  Enable the module for the apps you want to unlock: WhatsApp, Instagram, and/or Facebook.
5.  Force close or restart the target app(s) to apply the hooks.

---

## Technical Overview

The module operates using two primary layers:
* **DexKit Bridge:** Utilizes the DexKit library to scan the runtime APK on a fresh launch or update to locate targeted validation signatures dynamically.
* **Caching Manager:** Stores identified class and method signatures locally to bypass the scanning phase on subsequent launches. Caches are invalidated automatically when the target app or the module itself updates, triggering a fresh scan.

---

## Special Thanks

This project would not be possible without the incredible work done by the developers of the core hooking and analysis libraries:

* **[DexKit](https://github.com/LuckyPray/DexKit)** - For providing the powerful, high-performance APK signature searching capabilities.
* **[LSPosed](https://github.com/LSPosed)** - For the modern, cutting-edge ART hooking framework implementation.
* **[rovo89](https://github.com/rovo89/xposed)** - For the foundational revolutionary Xposed Framework that inspired modern Android modding.

---

## Disclaimer & Legal Notice

### 1. Terms of Service & Account Safety
This software is a third-party modification tool. It is not an official application, nor is it endorsed by, associated with, or sponsored by WhatsApp LLC, Instagram, Facebook, Meta Platforms Inc., or any of their affiliates. Using third-party modification frameworks (such as Xposed/LSPosed) to alter the behavior of official applications may violate the Terms of Service of WhatsApp, Instagram, and Facebook. Use this module at your own discretion and risk; the developers hold no responsibility for account bans, data loss, or restrictions imposed by the service provider.

### 2. Fair Use & Intellectual Property
All trademarks, service marks, logos, brand names, and copyrights referenced within this repository belong to their respective owners. This module modifies localized client-side application behavior for educational, research, and personalization purposes under fair-use provisions. No proprietary assets, decrypted source code, or media files are redistributed or hosted within this project.

### 3. Warranty & Liability
THIS SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE, AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES, OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT, OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
