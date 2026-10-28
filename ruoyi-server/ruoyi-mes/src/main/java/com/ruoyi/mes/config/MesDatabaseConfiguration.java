package com.ruoyi.mes.config;

import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Migrations must finish before RuoYi PostConstruct loads dictionaries/config/jobs. */
@Configuration
public class MesDatabaseConfiguration {

  @Bean(name = "mesMigrations")
  public Flyway migrations(DataSource dataSource) {
    Flyway flyway = Flyway.configure()
      .dataSource(dataSource)
      .locations("classpath:db/migration")
      .cleanDisabled(true)
      .baselineOnMigrate(false)
      .load();
    flyway.migrate();
    return flyway;
  }

  @Bean
  static BeanFactoryPostProcessor migrationsBeforeMyBatis() {
    return factory -> {
      var definition = factory.getBeanDefinition("sqlSessionFactory");
      var dependencies = new java.util.ArrayList<String>();
      if (definition.getDependsOn() != null) dependencies.addAll(
        java.util.List.of(definition.getDependsOn())
      );
      dependencies.add("mesMigrations");
      definition.setDependsOn(dependencies.toArray(String[]::new));
    };
  }
}
