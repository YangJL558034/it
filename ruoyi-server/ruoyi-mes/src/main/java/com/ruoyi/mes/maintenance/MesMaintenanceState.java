package com.ruoyi.mes.maintenance;

import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.mes.storage.MesDataPaths;
import java.io.IOException;
import java.nio.file.*;
import org.springframework.stereotype.Component;

@Component
public class MesMaintenanceState {

  private final MesDataPaths paths;
  private final java.util.concurrent.locks.ReentrantReadWriteLock barrier =
    new java.util.concurrent.locks.ReentrantReadWriteLock(true);

  public MesMaintenanceState(MesDataPaths paths) {
    this.paths = paths;
  }

  public boolean active() {
    return Files.exists(paths.resolve("maintenance/restore.lock"));
  }

  public synchronized void begin(String reason) throws IOException {
    if (active()) throw new ServiceException("系统已处于维护状态", 503);
    Path marker = paths.resolve("maintenance/restore.lock");
    Files.createDirectories(marker.getParent());
    Files.writeString(marker, reason, StandardOpenOption.CREATE_NEW);
  }

  public void finish() throws IOException {
    Files.delete(paths.resolve("maintenance/restore.lock"));
  }

  public void assertWritable() {
    if (active()) throw new ServiceException(
      "系统正在恢复或清理，暂时禁止业务写入",
      503
    );
  }

  public AutoCloseable enterRequest() {
    assertWritable();
    barrier.readLock().lock();
    try {
      assertWritable();
      return () -> barrier.readLock().unlock();
    } catch (RuntimeException e) {
      barrier.readLock().unlock();
      throw e;
    }
  }

  public AutoCloseable enterExclusive() {
    barrier.writeLock().lock();
    return () -> barrier.writeLock().unlock();
  }
}
