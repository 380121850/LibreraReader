# prebuilt/ — vendored offline binary cache

Holds every binary the build would otherwise fetch from the network, so a
machine can build **fully offline** (goal: no failures from network
restrictions on `git://git.ghostscript.com`, `jitpack.io`, slow mavenCentral).

Total ~374 MB. Committed as **regular git blobs — no Git LFS**: this repo's
remote is a GitHub *public fork*, which cannot push LFS objects
(`can not upload new objects to public fork`). The two archives that exceed
GitHub's 100 MB/file limit are therefore stored as **<=90 MB split parts**
and reassembled by the build scripts (`.gitattributes` only marks them `-text`).

## Layout

```
prebuilt/
  native/mupdf-1.23.7/<abi>/{libMuPDF.so, liblame.so}   # 8 .so, ~85 MB (RAW)
  gradle-cache/modules-2.tar.gz.part00 .part01           # ~158 MB split (90+68 MB)
  gradle/gradle-8.14.5-bin.zip.part00 .part01            # ~132 MB split (90+42 MB)
```

| Part | What | Original network source | Wired how | Regenerate |
| --- | --- | --- | --- | --- |
| `native/` | MuPDF + liblame native libs (4 ABIs) | `git clone git://git.ghostscript.com/mupdf` + ndk-build | `app/build.gradle` `jniLibs.srcDirs` (Gradle reads the .so directly — **no restore step**) | `Builder/prepare-native.sh` (fallback to `Builder/link_to_mupdf_1.23.7.sh`) |
| `gradle-cache/` | ALL Gradle/Maven deps + plugins (AGP, Kotlin, KSP, jitpack…) as Gradle's own cache (stored as split `.part*`) | mavenCentral / google / jitpack.io / gradlePluginPortal | `scripts/restore-cache.sh` reassembles (`cat .part*`) then extracts to `~/.gradle/caches/`; build with `--offline` | `scripts/vendor-cache.sh` (after an online build; auto-splits) |
| `gradle/` | Gradle 8.14.5 distribution (stored as split `.part*`) | services.gradle.org | `scripts/bootstrap-gradle.sh` reassembles (`cat .part*`) then seeds `~/.gradle/wrapper/dists/` | drop a new `gradle-<ver>-bin.zip` here (then split ≤90 MB) |

## Why split parts, not Git LFS

The gradle zip (~132 MB) and the dep-cache tarball (~158 MB) each exceed
GitHub's **100 MB/file** hard limit. Git LFS would normally handle that, but
**GitHub forbids LFS object uploads to public forks** — `git push` fails with
`can not upload new objects to public fork`. So both archives are stored as
two <=90 MB parts (`.part00`/`.part01`); the scripts `cat` them back together
on demand. The `.so` files are each <25 MB and need no splitting.

## Why the NATIVE cache, not a Maven repo

Modern AndroidX libraries publish **Gradle Module Metadata** with variant
artifacts (e.g. `androidx.room:room-runtime-android` ships its .aar as
`room-runtime-release.aar`). A hand-built file-Maven-repo cannot resolve these
(Gradle looks up variant files by their declared names and won't fall back to
the network once metadata is found), so the build fails. Vendoring Gradle's own
`modules-2` cache avoids this entirely — it is exactly what `--offline` uses,
so variant resolution is correct by construction. **Verified: a fresh
`GRADLE_USER_HOME` containing only this cache (reassembled from parts) builds
offline — BUILD SUCCESSFUL, APK contains all 4 ABI `libMuPDF.so`.**

## Normal build (persistent host that already has `~/.gradle`)

Nothing to do — the host cache + `--offline` just works. MuPDF `.so` are read
directly from `prebuilt/native/` by Gradle. `prebuilt/gradle-cache/` is only
needed on a machine with an empty `~/.gradle`.

## Fresh machine (empty `~/.gradle`, no network)

```bash
bash scripts/restore-cache.sh      # reassembles parts + extracts deps cache + seeds gradle dist
./gradlew --offline :app:assembleFdroidDebug
```

## Refreshing the cache (after changing dep/Gradle/MuPDF versions)

1. Build once **online**.
2. `bash scripts/vendor-cache.sh` — re-captures the dependency cache and
   **auto-splits** it into <=90 MB parts (removes the whole tarball).
3. `bash Builder/prepare-native.sh` — if you rebuilt MuPDF, new `.so` land here.
4. For a new Gradle version, split a fresh `gradle-<ver>-bin.zip` into
   <=90 MB parts under `gradle/` and update the version/hash in
   `scripts/bootstrap-gradle.sh`:
   ```bash
   split -b 90m -d -a 2 gradle-<ver>-bin.zip prebuilt/gradle/gradle-<ver>-bin.zip.part
   ```

## Committing (no Git LFS)

There are **no `filter=lfs` rules** anywhere. `.gitattributes` only marks the
binary types under `prebuilt/` as `-text` (so git never applies CRLF conversion
on the Samba-mounted working copy). Just:

```bash
git add prebuilt/ scripts/ Builder/ app/build.gradle .gitattributes .gitignore
git commit -m "..."
```

No `git lfs install`, no LFS setup of any kind.
