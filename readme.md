# WAPlusXposed

WAPlusXposed is a lightweight, efficient Xposed/LSPosed module designed to inject into and unlock premium customization features within WhatsApp. By utilizing dynamic APK analysis, the module seamlessly hooks target verification routines without relying on hardcoded offsets.

## Features

* Unlocks premium customization features including exclusive visual themes, native app launcher icons, premium sticker packs, custom ringtones, and much more.
* **Optimized Execution:** Uses DexKit to dynamically scan the target application combined with a robust caching manager to ensure fast initialization and compatibility across application updates.

---

## Requirements

* A rooted Android device or an environment supporting hook frameworks.
* **LSPosed Framework** (or a compatible Xposed implementation) installed and active.

---

## Installation

1.  Download and install the latest `WAPlusXposed` APK from the [Releases](https://github.com/RevealedSoulEven/WAPlusXposed/releases) section.
2.  Open your **LSPosed** manager application.
3.  Navigate to the modules section, locate **WAPlus**, and toggle **Enable Module**.
5.  Force close or restart WhatsApp to apply the hooks.

---

## Technical Overview

The module operates using two primary layers:
* **DexKit Bridge:** Utilizes the DexKit library to scan the runtime APK on a fresh launch or update to locate targeted validation signatures dynamically.
* **Caching Manager:** Stores identified class and method signatures locally to bypass the scanning phase on subsequent launches.

---

## Special Thanks

This project would not be possible without the incredible work done by the developers of the core hooking and analysis libraries:

* **[DexKit](https://github.com/LuckyPray/DexKit)** - For providing the powerful, high-performance APK signature searching capabilities.
* **[LSPosed](https://github.com/LSPosed)** - For the modern, cutting-edge ART hooking framework implementation.
* **[rovo89](https://github.com/rovo89/xposed)** - For the foundational revolutionary Xposed Framework that inspired modern Android modding.

---

## Disclaimer & Legal Notice

### 1. Terms of Service & Account Safety
This software is a third-party modification tool. It is not an official application, nor is it endorsed by, associated with, or sponsored by WhatsApp LLC, Meta Platforms Inc., or any of their affiliates. Using third-party modification frameworks (such as Xposed/LSPosed) to alter the behavior of official applications may violate WhatsApp's Terms of Service. Use this module at your own discretion and risk; the developers hold no responsibility for account bans, data loss, or restrictions imposed by the service provider.

### 2. Fair Use & Intellectual Property
All trademarks, service marks, logos, brand names, and copyrights referenced within this repository belong to their respective owners. This module modifies localized client-side application behavior for educational, research, and personalization purposes under fair-use provisions. No proprietary assets, decrypted source code, or media files are redistributed or hosted within this project.

### 3. Warranty & Liability
THIS SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE, AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES, OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT, OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.