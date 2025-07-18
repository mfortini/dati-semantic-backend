package it.gov.innovazione.ndc.integration;

import it.gov.innovazione.ndc.config.ElasticConfigurator;
import it.gov.innovazione.ndc.harvester.AgencyRepositoryService;
import it.gov.innovazione.ndc.harvester.HarvesterService;
import it.gov.innovazione.ndc.harvester.model.index.SemanticAssetMetadata;
import it.gov.innovazione.ndc.harvester.service.RepositoryService;
import it.gov.innovazione.ndc.harvester.service.SemanticContentStatsService;
import it.gov.innovazione.ndc.harvester.service.startupjob.StartupJobsRunner;
import it.gov.innovazione.ndc.repository.TripleStoreProperties;
import org.elasticsearch.client.RestClient;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.mapping.IndexCoordinates;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.elasticsearch.ElasticsearchContainer;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static it.gov.innovazione.ndc.harvester.service.RepositoryUtils.asRepo;
import static it.gov.innovazione.ndc.integration.Containers.ELASTICSEARCH_PORT;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import({BaseIntegrationTest.TestConfig.class})
public class BaseIntegrationTest {

    private static final ElasticsearchContainer elasticsearchContainer =
        Containers.buildElasticsearchContainer();
    private static final GenericContainer virtuoso = Containers.buildVirtuosoContainer();
    private boolean harvested = false;
    private static final String REPO_URL = "http://testRepoURL";

    @LocalServerPort
    int port;

    @MockitoSpyBean
    ElasticsearchOperations elasticsearchOperations;

    @Autowired
    HarvesterService harvesterService;

    @MockitoSpyBean
    AgencyRepositoryService agencyRepositoryService;

    @MockitoSpyBean
    RepositoryService repositoryService;

    @MockitoSpyBean
    SemanticContentStatsService semanticContentStatsService;

    @Autowired
    TripleStoreProperties virtuosoProps;

    @BeforeAll
    public static void beforeAll() {
        virtuoso.start();
        elasticsearchContainer.start();
    }

    @BeforeEach
    public void beforeEach() throws IOException {
        if (!harvested) {
            dataIsHarvested();
            harvested = true;
        }
    }

    static void updateTestcontainersProperties(DynamicPropertyRegistry registry) {
        String url = "http://localhost:" + virtuoso.getMappedPort(Containers.VIRTUOSO_PORT);
        registry.add("virtuoso.sparql", () -> url + "/sparql");
        registry.add("virtuoso.sparql-graph-store", () -> url + "/sparql-graph-crud/");
        registry.add("elastic.test.exposed-port", () -> elasticsearchContainer.getMappedPort(ELASTICSEARCH_PORT));
    }

    private void dataIsHarvested() throws IOException {
        String dir = "src/test/resources/testdata";
        Path cloneDir = Path.of(dir);
        doReturn(cloneDir).when(agencyRepositoryService).cloneRepo(REPO_URL, null);
        doNothing().when(agencyRepositoryService).removeClonedRepo(cloneDir);
        doNothing().when(repositoryService).storeRightsHolders(any(), any());
        doNothing().when(semanticContentStatsService).saveStats();

        harvesterService.harvest(asRepo(REPO_URL));

        refreshAllIndicesUsedForBulkIndexing();

        elasticsearchOperations.indexOps(SemanticAssetMetadata.class).refresh();
    }

    private void refreshAllIndicesUsedForBulkIndexing() {
        ArgumentCaptor<IndexCoordinates> indexCaptor = ArgumentCaptor.forClass(IndexCoordinates.class);

        verify(elasticsearchOperations).bulkIndex(anyList(), indexCaptor.capture());
        Set<IndexCoordinates> referencedIndices = new HashSet<>(indexCaptor.getAllValues());
        referencedIndices.forEach(ic -> elasticsearchOperations.indexOps(ic).refresh());
    }

    @Configuration
    public static class TestConfig {

        @Value("${elastic.test.exposed-port:-1}")
        private int port;

        @Bean
        @Primary
        public RestClient testClient() {
            try {
                int elasticPort = port == -1 ? elasticsearchContainer.getMappedPort(Containers.ELASTICSEARCH_PORT) : port;
                return new ElasticConfigurator(
                        "localhost",
                        elasticPort,
                        "https",
                        Containers.ELASTIC_USERNAME,
                        Containers.ELASTIC_PASSWORD).client();
            } catch (Exception e) {
                // bad workaround for a bad design
                return mock(RestClient.class);
            }
        }

        @Bean
        @Primary
        public StartupJobsRunner startupJobsRunner() {
            return new StartupJobsRunner(List.of());
        }
    }
}
