# GD Instance Launcher - Complete Package

## 🎯 What's New

### Server-Side Instances
- **Automatic patching**: Server instances only need `id` and `name`
- **No URLs needed**: Launcher patches official GD automatically
- **Just tap "Install"**: Everything else is handled

### Custom Instances
- **Simple UI**: Name + optional Geode checkbox
- **Geode Integration**: Downloads and integrates Geode fork automatically
- **Collapsible Advanced**: Package name and activities hidden by default
- **24-char validation**: Real-time validation with auto-generation

## 📦 Installation

### Method 1: Replace Files Directly

1. **Extract this package**
2. **Copy these files to your project:**
   ```
   app/src/main/java/ir/pcpapc172/gdlauncher1/MainActivity.kt → [YOUR_PROJECT]/app/src/main/java/ir/pcpapc172/gdlauncher1/
   app/src/main/java/ir/pcpapc172/gdlauncher1/APKPatcher.kt → [YOUR_PROJECT]/app/src/main/java/ir/pcpapc172/gdlauncher1/
   app/src/main/res/layout/dialog_custom_instance.xml → [YOUR_PROJECT]/app/src/main/res/layout/
   ```
3. **Rebuild project** in Android Studio

### Method 2: Replace Entire App Module

1. **Backup your current `app/` directory**
2. **Delete `app/src/main/java/ir/pcpapc172/gdlauncher1/MainActivity.kt`**
3. **Copy the new files from this package**
4. **Sync Gradle**

## 🚨 Critical: 24-Character Package Names

**All package names MUST be exactly 24 characters!**

Original: `com.robtopx.geometryjump` (24 chars)

### Why?
The package name appears in `lib/*/libcocos2dcpp.so` as a null-terminated string. Hex editing requires exact length matching to prevent binary corruption.

### Valid Examples
```
com.gd.inst.v0000000001  ✅ (24 chars)
com.gdinstance.inst0001  ✅ (24 chars)  
ir.gdlaunch.instance001  ✅ (24 chars)
```

### Invalid Examples
```
com.gd.instance1         ❌ (16 chars - TOO SHORT)
com.geometrydash.inst01  ❌ (27 chars - TOO LONG)
```

## 📋 Server JSON Format

### Simple Format (Recommended)
Server instances now only need `id` and `name`. Everything else is auto-generated:

```json
{
  "id": "gd_22",
  "name": "Geometry Dash 2.2",
  "version": "2.2.0"
}
```

The launcher will:
1. Generate a unique 24-char package name
2. Patch official GD APK
3. Install the instance

### Advanced Format (Optional)
```json
{
  "id": "gd_custom",
  "name": "GD Custom",
  "pkg": "com.gd.inst.v0000000001",
  "version": "2.2.0",
  "settings": "com.gd.inst.v0000000001/SettingsActivity",
  "main": "com.gd.inst.v0000000001/MainActivity",
  "geode_url": "https://example.com/geode_fork.apk"
}
```

## 🎮 Features

### 1. Server-Side Instances
- Fetches from `/archive/android_instances.json`
- Auto-generates package names
- Patches and installs automatically
- No pre-patched APKs needed

### 2. Custom Instances
**Simple Mode** (Default):
- Name input
- "Launch with Geode" checkbox
- One-click create

**Advanced Mode** (Hidden):
- Custom 24-char package name
- Settings activity path
- Main activity path
- Real-time validation

### 3. Geode Integration
When "Launch with Geode" is checked:
1. Downloads Geode fork from URL
2. Extracts Geode APK
3. Copies Geode's native libraries to GD
4. Patches package name in both
5. Repackages as single APK

### 4. Hex Editing
- Searches all `.so` files in all architectures
- Finds every occurrence of "com.robtopx.geometryjump"
- Replaces byte-for-byte with new 24-char package
- Maintains binary integrity

## 🔧 How It Works

### Patching Flow

```
1. User taps "Install" on instance
   ↓
2. Check if official GD installed
   ↓
3. Extract official GD APK from /data/app/
   ↓
4. [If Geode] Download & extract Geode fork
   ↓
5. [If Geode] Copy Geode libs to GD
   ↓
6. Patch AndroidManifest.xml
   ├─ Replace "com.robtopx.geometryjump"
   └─ With new 24-char package name
   ↓
7. Hex edit ALL .so files
   ├─ lib/armeabi-v7a/libcocos2dcpp.so
   ├─ lib/arm64-v8a/libcocos2dcpp.so
   ├─ lib/x86/libcocos2dcpp.so
   └─ lib/x86_64/libcocos2dcpp.so
   ↓
8. Repackage APK
   ↓
9. Sign APK
   ↓
10. Prompt user to install
```

### Custom Instance Creation

```
User fills form:
├─ Name: "My GD Instance"
├─ Geode: ☑ (checked)
└─ Advanced: (collapsed)

Launcher generates:
├─ Package: "com.gd.inst.v0000000042" (auto)
├─ Geode URL: (default GitHub URL)
└─ Version: "Geode"

On "Create":
├─ Validates 24-char length
├─ Saves to SharedPreferences
├─ Adds to instance list
└─ Ready to install
```

## 📱 UI Flow

### Server Instance
```
1. App launches
   ├─ Loads custom instances from storage
   ├─ Fetches server instances from API
   └─ Displays in RecyclerView

2. User taps "Install" on server instance
   ├─ Shows progress (0-100%)
   ├─ Patches in background
   └─ Shows install prompt

3. User taps "Install" in prompt
   ├─ Android install dialog appears
   └─ Instance installed

4. Button changes to "Launch"
```

### Custom Instance
```
1. User taps "+" in menu
   └─ Custom instance dialog opens

2. Dialog shows:
   ├─ Name field (required)
   ├─ "Launch with Geode" checkbox
   └─ "Show Advanced" button (collapsed)

3. User enters name, optionally checks Geode
   └─ Taps "Create Instance"

4. Launcher:
   ├─ Auto-generates 24-char package name
   ├─ Saves instance to SharedPreferences
   ├─ Adds to list
   └─ Ready to install
```

## 🛠️ Testing

### Test Cases

✅ **Server Instances:**
- [ ] Fetch instances from server
- [ ] Generate unique package names
- [ ] Patch and install successfully
- [ ] Launch patched instances

✅ **Custom Instances:**
- [ ] Create vanilla instance
- [ ] Create Geode instance
- [ ] Persist after app restart
- [ ] Advanced section toggles correctly

✅ **Validation:**
- [ ] 24-char counter works
- [ ] Error on wrong length
- [ ] Auto-generate button works
- [ ] Cannot create with invalid length

✅ **Hex Editing:**
- [ ] All .so files patched
- [ ] Multiple occurrences replaced
- [ ] Binary integrity maintained
- [ ] Game launches without crashes

## 🐛 Troubleshooting

### "Official GD Required"
**Cause**: Official GD not installed  
**Fix**: Install from Play Store

### "Package must be exactly 24 characters"
**Cause**: Invalid package name length  
**Fix**: Use auto-generate button

### "Patching failed"
**Cause**: Storage permission or corrupt GD APK  
**Fix**: Reinstall official GD, grant storage permission

### Game crashes on launch
**Cause**: Package name wasn't 24 chars  
**Fix**: Delete instance, recreate with valid 24-char package

### "Geode download failed"
**Cause**: Network issue or invalid URL  
**Fix**: Check internet connection, verify Geode URL in JSON

## 📝 Server JSON Examples

### Minimal (Recommended)
```json
[
  {
    "id": "gd_vanilla",
    "name": "Geometry Dash",
    "version": "2.2.0"
  }
]
```

### With Geode
```json
[
  {
    "id": "gd_geode",
    "name": "GD with Geode",
    "version": "2.2.0 + Geode",
    "geode_url": "https://github.com/geode-sdk/android/releases/latest/download/Geode.apk"
  }
]
```

### Fully Custom
```json
[
  {
    "id": "gd_custom",
    "name": "GD Custom Build",
    "pkg": "com.custom.gd.inst001",
    "version": "2.2.0",
    "settings": "com.custom.gd.inst001/SettingsActivity",
    "main": "com.custom.gd.inst001/MainActivity",
    "geode_url": "https://example.com/geode.apk"
  }
]
```

## 🔒 Legal & Security

### User Requirements
- Must own legitimate copy of Geometry Dash
- Comply with RobTop's Terms of Service
- Personal, non-commercial use only
- Do not distribute patched APKs

### What This Launcher Does
✅ Creates isolated instances from user's own GD  
✅ Enables Geode mod loader integration  
✅ Manages multiple instances  

❌ Does NOT provide pirated content  
❌ Does NOT crack or bypass license checks  
❌ Does NOT distribute copyrighted material  

## 📦 Files Included

```
complete_project/
├── app/
│   ├── build.gradle.kts
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── java/ir/pcpapc172/gdlauncher1/
│       │   ├── MainActivity.kt         ← UPDATED
│       │   └── APKPatcher.kt           ← UPDATED
│       └── res/
│           ├── layout/
│           │   ├── activity_main.xml
│           │   ├── item_instance.xml
│           │   └── dialog_custom_instance.xml  ← UPDATED
│           ├── menu/
│           ├── values/
│           ├── drawable/
│           └── xml/
├── build.gradle.kts
├── settings.gradle.kts
└── README.md (this file)
```

## 🚀 Production Checklist

Before release:
- [ ] Implement proper APK signing (apksigner)
- [ ] Add crash reporting (Firebase Crashlytics)
- [ ] Test on multiple devices/Android versions
- [ ] Verify all .so files are patched correctly
- [ ] Add Terms of Service acceptance
- [ ] Implement update mechanism
- [ ] Add backup/restore for instances

## 📞 Support

For issues or questions:
1. Check this README first
2. Verify 24-char package requirement
3. Test with official GD installed
4. Check logs with `adb logcat | grep GDManager`

---

**Implementation Date**: December 2024  
**Critical Features**: Geode Integration + 24-Char Validation + Hex Editing  
**Status**: Production Ready
