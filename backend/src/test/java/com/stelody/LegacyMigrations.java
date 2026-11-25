package com.stelody;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipInputStream;

/** Frozen pre-squash scripts for upgrade and old-backup regression tests only. */
public final class LegacyMigrations {
  private static final String LOCATION = extract();

  private LegacyMigrations() {}

  public static String location() {
    return LOCATION;
  }

  private static String extract() {
    try {
      Path directory = Files.createTempDirectory("stelody-legacy-v35-");
      directory.toFile().deleteOnExit();
      try (var zip =
          new ZipInputStream(LegacyMigrations.class.getResourceAsStream("/db/legacy-v35.zip"))) {
        for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
          if (!entry.getName().matches("V[0-9]+__[a-z0-9_]+\\.sql"))
            throw new IOException("Invalid legacy fixture");
          Path script = directory.resolve(entry.getName());
          Files.copy(zip, script);
          script.toFile().deleteOnExit();
        }
      }
      return "filesystem:" + directory;
    } catch (IOException e) {
      throw new IllegalStateException("Cannot read frozen migration fixture", e);
    }
  }
}
