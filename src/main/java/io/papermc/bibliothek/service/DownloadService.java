package io.papermc.bibliothek.service;

import io.papermc.bibliothek.configuration.StorageConfiguration;
import io.papermc.bibliothek.database.model.Build;
import io.papermc.bibliothek.database.model.Project;
import io.papermc.bibliothek.database.model.Version;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URLConnection;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class DownloadService {
  private static final Logger LOGGER = LoggerFactory.getLogger(DownloadService.class);
  private static final HostnameVerifier HOSTNAME_VERIFIER = (hostname, session) -> true;
  private final StorageConfiguration configuration;

  @Autowired
  public DownloadService(final StorageConfiguration configuration) {
    this.configuration = configuration;
  }

  public Path resolve(
    final Project project,
    final Version version,
    final Build build,
    final Build.Download download
  ) throws IOException {
    final Path cached = this.resolvePath(this.configuration.cache(), project, version, build, download);
    if (!Files.isRegularFile(cached)) {
      Files.createDirectories(cached.getParent());

      boolean wasSuccessful = false;
      @Nullable List<IOException> sourceExceptions = null;
      dance: for (final StorageConfiguration.Source source : this.configuration.sources()) {
        switch (source.type()) {
          case LOCAL -> {
            final Path localPath = this.resolvePath(Path.of(source.value()), project, version, build, download);
            if (Files.isRegularFile(localPath)) {
              try {
                Files.copy(localPath, cached);
                LOGGER.info("Cached resource {} from {}", cached.getFileName(), source.name());
                wasSuccessful = true;
                break dance;
              } catch (final IOException e) {
                if (sourceExceptions == null) {
                  sourceExceptions = new ArrayList<>();
                }
                sourceExceptions.add(e);
              }
            }
          }
          case REMOTE -> {
            final URI uri = this.resolveUrl(source.value(), project, version, build, download);
            try {
              final URLConnection connection = uri.toURL().openConnection();
              if (connection instanceof final HttpsURLConnection https) {
                https.setHostnameVerifier(HOSTNAME_VERIFIER);
              }
              try (
                final ReadableByteChannel channel = Channels.newChannel(connection.getInputStream());
                final FileOutputStream output = new FileOutputStream(cached.toFile())
              ) {
                output.getChannel().transferFrom(channel, 0, Long.MAX_VALUE);
                LOGGER.info("Cached resource {} from {}", cached.getFileName(), source.name());
                wasSuccessful = true;
                break dance;
              }
            } catch (final IOException e) {
              if (sourceExceptions == null) {
                sourceExceptions = new ArrayList<>();
              }
              sourceExceptions.add(e);
            }
          }
        }
      }
      if (!wasSuccessful) {
        final IOException exception = new IOException("Could not resolve download via CDN or Local Storage");
        if (sourceExceptions != null) {
          for (final IOException sourceException : sourceExceptions) {
            exception.addSuppressed(sourceException);
          }
        }
        throw exception;
      }
    }
    return cached;
  }

  private Path resolvePath(
    final Path base,
    final Project project,
    final Version version,
    final Build build,
    final Build.Download download
  ) {
    return base
      .resolve(project.name())
      .resolve(version.name())
      .resolve(String.valueOf(build.number()))
      .resolve(download.name());
  }

  private URI resolveUrl(
    final String base,
    final Project project,
    final Version version,
    final Build build,
    final Build.Download download
  ) {
    return URI.create(String.format(
      "%s/%s/%s/%d/%s",
      base,
      project.name(),
      version.name(),
      build.number(),
      download.name()
    ));
  }
}
