package uk.gov.pay.card.app;

import com.codahale.metrics.graphite.GraphiteReporter;
import com.codahale.metrics.graphite.GraphiteSender;
import com.codahale.metrics.graphite.GraphiteUDP;
import com.codahale.metrics.health.HealthCheck;
import io.dropwizard.Application;
import io.dropwizard.configuration.EnvironmentVariableSubstitutor;
import io.dropwizard.configuration.SubstitutingSourceProvider;
import io.dropwizard.setup.Bootstrap;
import io.dropwizard.setup.Environment;
import uk.gov.pay.card.app.config.CardConfiguration;
import uk.gov.pay.card.db.CardInformationStore;
import uk.gov.pay.card.db.RangeSetCardInformationStore;
import uk.gov.pay.card.managed.CardInformationStoreManaged;
import uk.gov.pay.card.resources.CardIdResource;
import uk.gov.pay.card.resources.HealthCheckResource;
import uk.gov.pay.card.service.CardService;
import uk.gov.pay.logging.GovUkPayDropwizardRequestJsonLogLayoutFactory;
import uk.gov.pay.logging.LoggingFilter;
import uk.gov.pay.logging.LogstashConsoleAppenderFactory;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static java.util.EnumSet.of;
import static javax.servlet.DispatcherType.REQUEST;
import static uk.gov.pay.card.db.loader.BinRangeDataLoader.BinRangeDataLoaderFactory;

public class CardApi extends Application<CardConfiguration> {

    private static final String SERVICE_METRICS_NODE = "cardid";
    private static final int GRAPHITE_SENDING_PERIOD_SECONDS = 10;

    public static void main(String[] args) throws Exception {
        new CardApi().run(args);
    }

    @Override
    public void initialize(Bootstrap<CardConfiguration> bootstrap) {
        bootstrap.setConfigurationSourceProvider(
                new SubstitutingSourceProvider(
                        bootstrap.getConfigurationSourceProvider(),
                        new EnvironmentVariableSubstitutor(false)
                )
        );

        bootstrap.getObjectMapper().getSubtypeResolver().registerSubtypes(LogstashConsoleAppenderFactory.class);
        bootstrap.getObjectMapper().getSubtypeResolver().registerSubtypes(GovUkPayDropwizardRequestJsonLogLayoutFactory.class);
    }

    @Override
    public void run(CardConfiguration configuration, Environment environment) {
        CardInformationStore store = initialiseCardInformationStore(configuration);
        environment.healthChecks().register("informationstore", new HealthCheck() {
            @Override
            protected Result check() {
                return store.isReady() ? Result.healthy() : Result.unhealthy("Card information not yet loaded");
            }
        });

        initialiseMetrics(configuration, environment);

        environment.jersey().register(new HealthCheckResource(environment));
        environment.lifecycle().manage(new CardInformationStoreManaged(store));
        environment.jersey().register(new CardIdResource(new CardService(store)));

        environment.servlets().addFilter("LoggingFilter", new LoggingFilter(environment.metrics()))
                .addMappingForUrlPatterns(of(REQUEST), true, "/v1/api/card");
    }

    private void initialiseMetrics(CardConfiguration configuration, Environment environment) {
        GraphiteSender graphiteUDP = new GraphiteUDP(configuration.getGraphiteHost(), configuration.getGraphitePort());
        GraphiteReporter.forRegistry(environment.metrics())
                .prefixedWith(SERVICE_METRICS_NODE)
                .build(graphiteUDP)
                .start(GRAPHITE_SENDING_PERIOD_SECONDS, TimeUnit.SECONDS);

    }

    private CardInformationStore initialiseCardInformationStore(CardConfiguration configuration) {
        return new RangeSetCardInformationStore(List.of(
                BinRangeDataLoaderFactory.worldpay(configuration.getWorldpayDataLocation()),
                BinRangeDataLoaderFactory.discover(configuration.getDiscoverDataLocation()),
                BinRangeDataLoaderFactory.testCards(configuration.getTestCardDataLocation())
        ));
    }
}
