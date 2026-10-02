import java.util.Map;
import org.apache.kafka.streams.state.RocksDBConfigSetter;
import org.rocksdb.*;

public final class BoundedRocks implements RocksDBConfigSetter {
  private Cache cache;

  public void setConfig(String name, Options options, Map<String, Object> config) {
    cache = new LRUCache(32L * 1024 * 1024);
    ((BlockBasedTableConfig) options.tableFormatConfig()).setBlockCache(cache);
    options.setWriteBufferSize(16L * 1024 * 1024).setMaxWriteBufferNumber(2);
  }

  public void close(String name, Options options) {
    if (cache != null) cache.close();
  }
}
