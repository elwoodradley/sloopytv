#!/bin/zsh
# Cut a SloopyTV release: bump the version, build the signed APK, tag, and publish it on
# GitHub Releases as `sloopytv.apk`, which is the fixed name the in-app updater looks for.
#
#   scripts/release.sh 0.1.2 "What changed, one line"
#
# Needs: JDK 17 and the Android SDK at the Homebrew paths below, keystore/keystore.properties,
# and `gh auth login` done once.
set -euo pipefail
VERSION="${1:?usage: release.sh <version> [notes]}"
NOTES="${2:-SloopyTV $VERSION}"
cd "$(dirname "$0")/.."

export JAVA_HOME=/opt/homebrew/opt/openjdk@17
export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$PATH"

[[ -f keystore/keystore.properties ]] || { echo "keystore/keystore.properties missing; cannot sign"; exit 1 }
git diff --quiet || { echo "commit or stash your changes first"; exit 1 }

# versionCode must only ever go up; derive it from the version so it stays monotonic.
IFS=. read -r MAJ MIN PAT <<< "$VERSION"
CODE=$(( MAJ * 10000 + MIN * 100 + PAT ))
sed -i '' "s/versionCode = [0-9]*/versionCode = $CODE/; s/versionName = \"[^\"]*\"/versionName = \"$VERSION\"/" app/build.gradle.kts
grep -E "versionCode|versionName" app/build.gradle.kts | head -2

./gradlew :core:test :app:assembleRelease -q
mkdir -p dist
cp app/build/outputs/apk/release/app-release.apk "dist/sloopytv-$VERSION.apk"
cp app/build/outputs/apk/release/app-release.apk dist/sloopytv.apk
"$ANDROID_HOME/build-tools/35.0.0/apksigner" verify dist/sloopytv.apk

git add app/build.gradle.kts
git commit -q -m "Release $VERSION"
git tag "v$VERSION"
git push -q origin main "v$VERSION"
gh release create "v$VERSION" dist/sloopytv.apk --title "SloopyTV $VERSION" --notes "$NOTES"
echo "Published. Installed copies will offer $VERSION within six hours, or at once from Settings › About › Check for updates."
