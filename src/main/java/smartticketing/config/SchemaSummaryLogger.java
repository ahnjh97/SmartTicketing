package smartticketing.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.BeanFactoryAware;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

/** Hibernate 스키마 준비 전후의 테이블 이름만 비교한다. SQL/접속 정보는 기록하지 않는다. */
@Slf4j
@Component
public class SchemaSummaryLogger implements BeanPostProcessor, BeanFactoryAware {
    private BeanFactory beanFactory;
    private final Map<String, Set<String>> before = new ConcurrentHashMap<>();

    @Override
    public void setBeanFactory(BeanFactory beanFactory) { this.beanFactory = beanFactory; }

    @Override
    public Object postProcessBeforeInitialization(Object bean, String beanName) {
        if (bean instanceof LocalContainerEntityManagerFactoryBean) {
            try { before.put(beanName, tables()); }
            catch (SQLException e) { log.warn("[DB 구조] 생성 전 테이블 목록 확인 실패 ({})", e.getClass().getSimpleName()); }
        }
        return bean;
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) {
        if (bean instanceof LocalContainerEntityManagerFactoryBean) {
            var previous = before.remove(beanName);
            if (previous != null) {
                try {
                    var current = tables();
                    var created = new TreeSet<>(current);
                    created.removeAll(previous);
                    log.info("[DB 구조] 테이블 총 {}개 | 새로 생성 {}개{}", current.size(), created.size(),
                            created.isEmpty() ? "" : " | " + String.join(", ", created));
                } catch (SQLException e) { log.warn("[DB 구조] 생성 후 테이블 목록 확인 실패 ({})", e.getClass().getSimpleName()); }
            }
        }
        return bean;
    }

    private Set<String> tables() throws SQLException {
        var result = new TreeSet<String>();
        try (var connection = beanFactory.getBean(DataSource.class).getConnection();
             var tables = connection.getMetaData().getTables(connection.getCatalog(), null, "%", new String[]{"TABLE"})) {
            while (tables.next()) result.add(tables.getString("TABLE_NAME"));
        }
        return result;
    }
}
