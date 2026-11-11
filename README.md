# CharacterTalk (Android)

Native Kotlin + Jetpack Compose character chat app with voice support.

## Build APK with GitHub Actions (No Computer Needed)

You can build the APK completely from your phone using GitHub.

### Step-by-step (from your S26 Ultra)

1. **Download this project**
   - Download the `CharacterTalk_Ready.zip` (or the latest version I gave you)
   - Unzip it using your phone’s file manager (Files by Google, Samsung My Files, etc.)

2. **Create a GitHub account** (if you don’t have one)
   - Go to https://github.com in your browser and sign up

3. **Create a new repository**
   - Open the GitHub website or GitHub mobile app
   - Tap **+** → **New repository**
   - Name it something like `CharacterTalk`
   - Leave it **Public**
   - Do **not** check “Add a README”
   - Create the repository

4. **Upload the project files**
   - On the new empty repository page, tap **uploading an existing file**
   - Select **all** the files and folders inside the CharacterTalk folder
     (including the hidden `.github` folder if your file manager shows it)
   - Commit the files

   > Tip: If your file manager hides the `.github` folder, you can use the GitHub mobile app or a better file manager, or upload the zip and extract later. The easiest is usually to use a computer once, but pure phone is possible with a good file manager.

5. **Trigger the build**
   - Go to the **Actions** tab of your repository
   - You should see the workflow “Build CharacterTalk APK”
   - Tap it → **Run workflow** → **Run workflow**
   - Wait 3–6 minutes for it to finish (green checkmark)

6. **Download the APK**
   - When the workflow finishes, open the completed run
   - Scroll down to **Artifacts**
   - Download **CharacterTalk-APK**
   - Unzip the downloaded file → you will get `app-debug.apk`

7. **Install on your S26 Ultra**
   - Open the APK file
   - Allow “Install unknown apps” for your browser/file manager if asked
   - Install
   - Open CharacterTalk and grant Microphone permission

---

## Features
- Character profiles with description, personality and scenario
- Separate local chat history per character
- OpenRouter-compatible API
- Encrypted API keys (Android Keystore)
- Speech-to-text + optional TTS
- Voice mode

## First launch
1. Go to **Settings**
2. Enter your OpenRouter API key
3. Base URL: `https://openrouter.ai/api/v1`
4. Choose a model (examples):
   - `qwen/qwen3-4b:free`
   - `google/gemma-3-4b-it:free`
5. Save → Create a character → Start chatting

Enjoy 💜
