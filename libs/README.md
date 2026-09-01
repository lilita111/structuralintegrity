# libs/

`build.gradle.kts` compiles against two sable jars that live here and are **not**
committed. They come straight out of the pack rather than a maven repo, so the
classes compiled against are byte-for-byte the ones that will be loaded at runtime,
and building this mod downloads nothing.

`sable-companion-common` is shipped jar-in-jar inside the sable jar, which is why
it has to be unzipped back out.

To repopulate this directory from an installed pack:

```bash
PACK=/c/Users/Iaotyra/AppData/Roaming/ModrinthApp/profiles/create_benchmark/mods
SABLE=$PACK/sable-neoforge-1.21.1-2.0.5.jar

cp "$SABLE" libs/
unzip -o -j "$SABLE" "META-INF/jarjar/sable-companion-common-1.21.1-1.6.0.jar" -d libs/
```

Expected afterwards:

```
libs/sable-neoforge-1.21.1-2.0.5.jar
libs/sable-companion-common-1.21.1-1.6.0.jar
```

Both are `compileOnly`, so neither is bundled into the output jar - sable is a
required dependency and provides them at runtime.

## What is used from them

| Class | From | Used for |
| --- | --- | --- |
| `dev.ryanhcode.sable.api.SubLevelAssemblyHelper` | sable | `assembleBlocks` - the fall |
| `dev.ryanhcode.sable.api.sublevel.SubLevelContainer` | sable | null-check before assembling |
| `dev.ryanhcode.sable.sublevel.ServerSubLevel` | sable | return value, mass check |
| `dev.ryanhcode.sable.companion.math.BoundingBox3i` | companion | `from(blocks)`, the assembly bounds |

If the pack's sable version changes, update the two filenames in
`build.gradle.kts` to match. `neoforge.mods.toml` currently accepts sable `[1.0,)`,
which is looser than the API actually used here.
