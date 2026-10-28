package com.ruoyi.mes.storage;

import java.io.IOException;
import java.nio.file.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class MesDataPaths {

  private final Path root;

  public MesDataPaths(@Value("${mes.data-directory}") String directory)
    throws IOException {
    root = Path.of(directory).toAbsolutePath().normalize();
    Files.createDirectories(root);
  }

  public Path root() {
    return root;
  }

  public Path resolve(String relative) {
    Path path = root.resolve(relative).normalize();
    if (
      !path.startsWith(root) || path.equals(root)
    ) throw new IllegalArgumentException("数据路径无效");
    return path;
  }

  public void atomicWrite(Path destination, byte[] data) throws IOException {
    if (
      !destination.toAbsolutePath().normalize().startsWith(root)
    ) throw new IllegalArgumentException("文件路径超出数据目录");
    Files.createDirectories(destination.getParent());
    Path temporary = Files.createTempFile(
      destination.getParent(),
      ".writing-",
      ".tmp"
    );
    try {
      Files.write(temporary, data);
      try {
        Files.move(
          temporary,
          destination,
          StandardCopyOption.ATOMIC_MOVE,
          StandardCopyOption.REPLACE_EXISTING
        );
      } catch (AtomicMoveNotSupportedException e) {
        Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING);
      }
    } finally {
      Files.deleteIfExists(temporary);
    }
  }

  public static String sha256(byte[] bytes) {
    try {
      return java.util.HexFormat.of()
        .formatHex(
          java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
        );
    } catch (java.security.NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  public static String sha256(Path file) throws IOException {
    try (var input = Files.newInputStream(file)) {
      var hash = java.security.MessageDigest.getInstance("SHA-256");
      byte[] buffer = new byte[65536];
      int n;
      while ((n = input.read(buffer)) != -1) hash.update(buffer, 0, n);
      return java.util.HexFormat.of().formatHex(hash.digest());
    } catch (java.security.NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
