package tech.calcifer.ragequit;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteDataSource;

import javax.sql.DataSource;

@Configuration
public class StorageConfiguration {
    @Bean
    DataSource dataSource(RageQuitProperties properties) {
        var config = new SQLiteConfig();
        config.enforceForeignKeys(true);
        config.setBusyTimeout(2000);
        config.setJournalMode(SQLiteConfig.JournalMode.DELETE);
        config.setSynchronous(SQLiteConfig.SynchronousMode.FULL);
        config.setTransactionMode(SQLiteConfig.TransactionMode.DEFERRED);
        var source = new SQLiteDataSource(config);
        source.setUrl("jdbc:sqlite:" + properties.database());
        return source;
    }
}
