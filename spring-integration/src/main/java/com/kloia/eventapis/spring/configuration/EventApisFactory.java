package com.kloia.eventapis.spring.configuration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kloia.eventapis.api.IUserContext;
import com.kloia.eventapis.api.impl.EmptyUserContext;
import com.kloia.eventapis.cassandra.CassandraSession;
import com.kloia.eventapis.common.CommandExecutionInterceptor;
import com.kloia.eventapis.common.EventExecutionInterceptor;
import com.kloia.eventapis.common.OperationContext;
import com.kloia.eventapis.kafka.KafkaOperationRepository;
import com.kloia.eventapis.kafka.KafkaOperationRepositoryFactory;
import com.kloia.eventapis.kafka.KafkaProperties;
import com.kloia.eventapis.kafka.PublishedEventWrapper;
import com.kloia.eventapis.pojos.Operation;
import com.kloia.eventapis.spring.filter.OpContextFilter;
import feign.RequestInterceptor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.Consumer;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Scope;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.listener.AbstractMessageListenerContainer;
import org.springframework.kafka.listener.ConcurrentMessageListenerContainer;
import org.springframework.kafka.listener.config.ContainerProperties;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.transaction.PlatformTransactionManager;

import javax.annotation.PreDestroy;
import javax.servlet.DispatcherType;
import java.util.EnumSet;
import java.util.Map;

@Slf4j
@Configuration
@Import(SpringKafkaOpListener.class)
public class EventApisFactory {

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    private EventApisConfiguration eventApisConfiguration;

    @Autowired
    private CassandraSession cassandraSession;

    @Bean
    public OperationContext createOperationContext() {
        return new OperationContext();
    }

    @Bean
    CassandraSession cassandraSession() {
        return new CassandraSession(eventApisConfiguration.getStoreConfig());
    }

    @PreDestroy
    public void destroy() {
        cassandraSession.destroy();
    }


    @Bean
    public FilterRegistrationBean createOpContextFilter(@Autowired OperationContext operationContext) {
        FilterRegistrationBean registration = new FilterRegistrationBean();
        registration.setFilter(new OpContextFilter(operationContext));
//        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        registration.setDispatcherTypes(EnumSet.allOf(DispatcherType.class));
        return registration;
    }

    @Bean
    @Scope("prototype")
    public RequestInterceptor opIdInterceptor(@Autowired OperationContext operationContext) {
        return template -> {
            String key = operationContext.getContextOpId();
            if (key != null) {
                template.header(OpContextFilter.OP_ID_HEADER, key);
//                template.header(OperationContext.OP_ID, key); // legacy
            }
        };
    }

    @Bean
    public CommandExecutionInterceptor createCommandExecutionInterceptor(@Autowired KafkaOperationRepository kafkaOperationRepository,
                                                                         @Autowired OperationContext operationContext) {
        return new CommandExecutionInterceptor(kafkaOperationRepository, operationContext);
    }

    @Bean
    public EventExecutionInterceptor createEventExecutionInterceptor(@Autowired KafkaOperationRepository kafkaOperationRepository,
                                                                     @Autowired OperationContext operationContext,
                                                                     @Autowired IUserContext userContext) {
        return new EventExecutionInterceptor(kafkaOperationRepository, operationContext, userContext);
    }

    @Bean
    public KafkaOperationRepositoryFactory kafkaOperationRepositoryFactory(@Autowired OperationContext operationContext,
                                                                           IUserContext userContext) {
        KafkaProperties eventBus = eventApisConfiguration.getEventBus();
        return new KafkaOperationRepositoryFactory(eventBus, userContext, operationContext);
    }

    @Bean
    public KafkaOperationRepository kafkaOperationRepository(KafkaOperationRepositoryFactory kafkaOperationRepositoryFactory) {
        return kafkaOperationRepositoryFactory.createKafkaOperationRepository(objectMapper);
    }

    @Bean
    public EventMessageConverter eventMessageConverter(OperationContext operationContext, IUserContext userContext, KafkaOperationRepository kafkaOperationRepository) {
        return new EventMessageConverter(objectMapper, operationContext, userContext, kafkaOperationRepository);
    }

    @Bean
    public ConsumerFactory<String, PublishedEventWrapper> kafkaConsumerFactory(KafkaOperationRepositoryFactory kafkaOperationRepositoryFactory) {
        return new EventApisConsumerFactory<String, PublishedEventWrapper>(eventApisConfiguration, true) {
            @Override
            public Consumer<String, PublishedEventWrapper> createConsumer() {
                return kafkaOperationRepositoryFactory.createEventConsumer(objectMapper);
            }
        };
    }

    @Bean
    public ConsumerFactory<String, Operation> kafkaOperationsFactory(KafkaOperationRepositoryFactory kafkaOperationRepositoryFactory) {
        return new EventApisConsumerFactory<String, Operation>(eventApisConfiguration, false) {
            @Override
            public Consumer<String, Operation> createConsumer() {
                return kafkaOperationRepositoryFactory.createOperationConsumer(objectMapper);
            }
        };
    }

    @Bean({"eventsKafkaListenerContainerFactory", "kafkaListenerContainerFactory"})
    public ConcurrentKafkaListenerContainerFactory<String, PublishedEventWrapper> eventsKafkaListenerContainerFactory(
            EventMessageConverter eventMessageConverter, ConsumerFactory<String, PublishedEventWrapper> consumerFactory) {

        ConcurrentKafkaListenerContainerFactory<String, PublishedEventWrapper> factory = new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory);
        factory.setConcurrency(eventApisConfiguration.getEventBus().getConsumer().getEventConcurrency());
        factory.setMessageConverter(eventMessageConverter);
        factory.getContainerProperties().setPollTimeout(3000);
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(eventApisConfiguration.getEventBus().getConsumer().getEventSchedulerPoolSize());
        scheduler.setBeanName("EventsFactory-Scheduler");
        scheduler.initialize();

        factory.getContainerProperties().setScheduler(scheduler);
        factory.getContainerProperties().setAckMode(AbstractMessageListenerContainer.AckMode.RECORD);
        return factory;
    }

    @Bean("operationsKafkaListenerContainerFactory")
    public EventApisKafkaListenerContainerFactory operationsKafkaListenerContainerFactory(
            ConsumerFactory<String, Operation> consumerFactory,
            PlatformTransactionManager platformTransactionManager) {
        EventApisKafkaListenerContainerFactory factory
                = new EventApisKafkaListenerContainerFactory(consumerFactory);
        RetryTemplate retryTemplate = new RetryTemplate();
        factory.setRetryTemplate(retryTemplate);
        factory.getContainerProperties().setPollTimeout(3000L);
        factory.getContainerProperties().setAckOnError(false);
        factory.setConcurrency(eventApisConfiguration.getEventBus().getConsumer().getOperationSchedulerPoolSize());
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(eventApisConfiguration.getEventBus().getConsumer().getOperationSchedulerPoolSize());
        scheduler.setBeanName("OperationsFactory-Scheduler");
        scheduler.initialize();
        factory.getContainerProperties().setScheduler(scheduler);
        ThreadPoolTaskScheduler consumerScheduler = new ThreadPoolTaskScheduler();
        consumerScheduler.setPoolSize(eventApisConfiguration.getEventBus().getConsumer().getOperationSchedulerPoolSize());
        consumerScheduler.setBeanName("OperationsFactory-ConsumerScheduler");
        consumerScheduler.initialize();

        factory.getContainerProperties().setConsumerTaskExecutor(consumerScheduler);
        factory.getContainerProperties().setAckMode(AbstractMessageListenerContainer.AckMode.RECORD);
        factory.getContainerProperties().setTransactionManager(platformTransactionManager);
        return factory;
    }

    @Bean
    @ConditionalOnMissingBean(IUserContext.class)
    public IUserContext getUserContext() {
        return new EmptyUserContext();
    }

    public static class EventApisKafkaListenerContainerFactory extends ConcurrentKafkaListenerContainerFactory<String, Operation> {
        private final ConsumerFactory<String, Operation> consumerFactory;
        private Integer concurrency;

        /**
         * Specify the container concurrency.
         * @param concurrency the number of consumers to create.
         * @see ConcurrentMessageListenerContainer#setConcurrency(int)
         */
        public void setConcurrency(Integer concurrency) {
            this.concurrency = concurrency;
        }

        public EventApisKafkaListenerContainerFactory(ConsumerFactory<String, Operation> consumerFactory) {
            this.consumerFactory = consumerFactory;
        }

        @Override
        public ConsumerFactory<String, Operation> getConsumerFactory() {
            return consumerFactory;
        }

        @Override
        protected void initializeContainer(ConcurrentMessageListenerContainer<String, Operation> instance) {
            if (this.concurrency != null) {
                instance.setConcurrency(this.concurrency);
            }
            ContainerProperties properties = instance.getContainerProperties();
            BeanUtils.copyProperties(
                    this.getContainerProperties(), properties,
                    "topics", "topicPartitions", "topicPattern",
                    "messageListener", "ackCount", "ackTime", "ackMode");
            if (this.getContainerProperties().getAckCount() > 0) {
                properties.setAckCount(this.getContainerProperties().getAckCount());
            }
            if (this.getContainerProperties().getAckTime() > 0) {
                properties.setAckTime(this.getContainerProperties().getAckTime());
            }
        }
    }

    public abstract static class EventApisConsumerFactory<K, V> implements ConsumerFactory<K, V> {
        private final EventApisConfiguration eventApisConfiguration;
        private final boolean autoCommit;

        public EventApisConsumerFactory(EventApisConfiguration eventApisConfiguration, boolean autoCommit) {
            this.autoCommit = autoCommit;
            this.eventApisConfiguration = eventApisConfiguration;
        }

        @Override
        public Consumer<K, V> createConsumer(String clientIdSuffix) {
            return createConsumer();
        }

        @Override
        public Consumer<K, V> createConsumer(String groupId, String clientIdSuffix) {
            return createConsumer();
        }

        @Override
        public boolean isAutoCommit() {
            return autoCommit;
        }

        @Override
        public Map<String, Object> getConfigurationProperties() {
            return eventApisConfiguration.getEventBus().buildConsumerProperties();

        }
    }
}
