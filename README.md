# MPU ERP – Android App

Official Android app for the **Mind Power University (MPU) ERP**. It wraps the ERP web portal in a native Android shell built with **Kotlin** and **Jetpack Compose**, adding native behaviour like session persistence, pull-to-refresh, offline handling and proper keyboard control.

Published on the Google Play Store.

---

## Features

- **ERP in a native shell** – the full ERP web portal inside a Compose-hosted `WebView`
- **Auto-login / session persistence** – users stay logged in across app restarts
- **Pull-to-refresh** – native swipe-to-refresh that only triggers when the page is scrolled to the top
- **Offline handling** – friendly offline state instead of a blank/broken page
- **Loading spinner** – shown during page loads and hidden once content is ready
- **Keyboard control** – keyboard is dismissed automatically after login/navigation (no IME popping up on every touch)
- **Role support** – works with both student and admin ERP logins

---

## Tech Stack

| Layer       | Technology                          |
|-------------|-------------------------------------|
| Language    | Kotlin                              |
| UI          | Jetpack Compose                     |
| Web content | Android `WebView` (via `AndroidView`) |
| Build       | Gradle (Kotlin DSL)                 |
| Min SDK     | Set in `app/build.gradle.kts`       |

---

## Project Structure

```
mpu-erp-android/
├── app/
│   ├── src/main/
│   │   ├── java/.../
│   │   │   ├── MainActivity.kt        # Entry point, hosts Compose UI
│   │   │   └── ui/                    # WebView composable, refresh, offline UI
│   │   ├── res/                       # Icons, themes, strings
│   │   └── AndroidManifest.xml
│   └── build.gradle.kts
├── build.gradle.kts
├── settings.gradle.kts
└── README.md
```

> Adjust folder/file names above to match your actual repo.

---

## Getting Started

### Prerequisites

- Android Studio (latest stable)
- JDK 17+
- An Android device or emulator (API 24+ recommended)

### Setup

```bash
# 1. Clone the repo
git clone https://github.com/sachin-cyber904/mpu-erp-android.git
cd mpu-erp-android

# 2. Open in Android Studio and let Gradle sync

# 3. Run on a device/emulator
./gradlew installDebug
```

### Build a release APK / AAB

```bash
./gradlew assembleRelease     # APK
./gradlew bundleRelease       # AAB (for Play Store)
```

Add your signing config in `app/build.gradle.kts` or `keystore.properties` (do **not** commit keystores).

---

## Configuration

Set the ERP URL in one place (e.g. `Constants.kt` or `BuildConfig`):

```kotlin
const val ERP_URL = "https://your-erp-url.com"
```

---

## How It Works

1. `MainActivity` loads a Compose screen containing the `WebView`.
2. Cookies/session are persisted so the user is auto-logged in on next launch.
3. JavaScript bridge reports scroll position (`canScrollUp`) so pull-to-refresh only fires at the top of the page.
4. On navigation, the app hides the keyboard and clears WebView focus.
5. If there is no network, an offline screen is shown with a retry option.

---

## Known Fixes (Changelog Highlights)

- **Admin login flash** – fixed login page briefly showing before auto-login redirect (spinner hide-timer race with slower admin server response).
- **Stuck pull-to-refresh after logout** – scroll state is now reset immediately on logout.
- **Keyboard popping on every tap** – added a JS blur guard plus native `hideKeyboard()` / `clearFocus()` on page navigation.

---

## Contributing

1. Fork the repo
2. Create a branch: `git checkout -b feature/your-feature`
3. Commit your changes: `git commit -m "Add your feature"`
4. Push and open a Pull Request

---

## Author

**Sachin**
- GitHub: [sachin-cyber904](https://github.com/sachin-cyber904)
- Email: sachinbhatt865@gmail.com

---

## License

This project is proprietary to Mind Power University. All rights reserved.
