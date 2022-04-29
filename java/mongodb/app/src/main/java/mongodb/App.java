package mongodb;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import mongodb.SubscriberHelpers.PrintDocumentSubscriber;

import java.util.concurrent.TimeUnit;

import com.mongodb.ConnectionString;
import com.mongodb.reactivestreams.client.MongoClients;
import com.mongodb.reactivestreams.client.MongoClient;
import com.mongodb.reactivestreams.client.MongoDatabase;
import com.mongodb.reactivestreams.client.MongoCollection;
import com.mongodb.MongoCredential;
import com.mongodb.AwsCredential;
import com.mongodb.MongoClientSettings;

import org.bson.Document;

import software.amazon.awssdk.services.sts.StsClient;
import software.amazon.awssdk.services.sts.model.AssumeRoleRequest;
import software.amazon.awssdk.services.sts.model.AssumeRoleWithWebIdentityRequest;
import software.amazon.awssdk.services.sts.model.AssumeRoleWithWebIdentityResponse;
import software.amazon.awssdk.services.sts.model.AssumeRoleResponse;
import software.amazon.awssdk.services.sts.model.Credentials;
import java.util.function.Supplier;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

public class App {
    public static void main(String[] args) throws InterruptedException {
        Logger logger = LoggerFactory.getLogger("examples.java.mongodb");
        logger.info("Starting ... ");
        String mongodb_url = System.getenv("MONGO_URL");
        logger.info("MONGO_URL={}", mongodb_url);

        ConnectionString connString = new ConnectionString(mongodb_url);

        MongoClientSettings settings = MongoClientSettings.builder()
                .applyConnectionString(connString)
                .applyToConnectionPoolSettings(builder -> builder
                        // force new connection every 30 seconds to check refreshing credentials
                        .maxConnectionIdleTime(30, TimeUnit.SECONDS)
                        .minSize(0)
                        .maxSize(10))
                .retryWrites(true)
                .build();

        // configure fetching&refreshing credentials in EKS with IAM role
        if (settings.getCredential().getAuthenticationMechanism() == com.mongodb.AuthenticationMechanism.MONGODB_AWS) {
            String AWS_ROLE_ARN = System.getenv("AWS_ROLE_ARN");
            String AWS_WEB_IDENTITY_TOKEN_FILE = System.getenv("AWS_WEB_IDENTITY_TOKEN_FILE");
            if (AWS_ROLE_ARN != "" && AWS_WEB_IDENTITY_TOKEN_FILE != "") {
                MongoCredential credential = MongoCredential.createAwsCredential(null, null)
                        .withMechanismProperty(MongoCredential.AWS_CREDENTIAL_PROVIDER_KEY,
                                new MongoAwsCredentialSupplier(
                                        new CredentialsSupplier(AWS_ROLE_ARN, "test", AWS_WEB_IDENTITY_TOKEN_FILE)));

                settings = MongoClientSettings.builder(settings).credential(credential).build();
            }
        }

        MongoClient mongoClient = MongoClients.create(settings);
        MongoDatabase database = mongoClient.getDatabase("test");
        MongoCollection<Document> collection = database.getCollection("test");

        while (true) {
            // Document doc = collection.find(eq("test", "test")).first();
            logger.info("Querying Database: {}", database.getName());
            collection.find().subscribe(new PrintDocumentSubscriber());
            TimeUnit.SECONDS.sleep(60);
        }

        // mongoClient.close();
    }

    private static class MongoAwsCredentialSupplier implements Supplier<AwsCredential> {
        private final Supplier<Credentials> wrappedSupplier;
        private Credentials credentials;
        private final Logger logger;

        public MongoAwsCredentialSupplier(Supplier<Credentials> wrappedSupplier) {
            this.wrappedSupplier = wrappedSupplier;
            this.logger = LoggerFactory.getLogger("examples.java.mongodb.credentialsSupplierWrapper");
            credentials = wrappedSupplier.get();
        }

        @Override
        public AwsCredential get() {
            synchronized (this) {
                // alternatively, could start a thread that keeps the credentials up to date, in
                // order to avoid blocking
                logger.info("in get()");
                if (credentials.expiration().isBefore(Instant.now().plusSeconds(60))) {
                    logger.info("in get() refreshing creds");
                    credentials = wrappedSupplier.get();
                }
            }
            return new AwsCredential(
                    credentials.accessKeyId(),
                    credentials.secretAccessKey(),
                    credentials.sessionToken());
        }
    }

    private static class CredentialsSupplier implements Supplier<Credentials> {
        private final String roleArn;
        private final String roleSessionName;
        private final Path webIdentityTokenFile;
        private final Logger logger;

        public CredentialsSupplier(String roleArn, String roleSessionName,
                String webIdentityTokenFile) {
            this.roleArn = roleArn;
            this.roleSessionName = roleSessionName;
            this.webIdentityTokenFile = Path.of(webIdentityTokenFile);
            this.logger = LoggerFactory.getLogger("examples.java.mongodb.credentialsSupplier");
        }

        @Override
        public Credentials get() {
            String webIdentityToken = "";
            try {
                webIdentityToken = Files.readString(webIdentityTokenFile);
            } catch (IOException e) {
                e.printStackTrace();
            }
            StsClient stsClient = StsClient.builder().build();

            AssumeRoleWithWebIdentityRequest roleRequest = AssumeRoleWithWebIdentityRequest.builder()
                    .roleArn(roleArn)
                    .webIdentityToken(webIdentityToken)
                    .roleSessionName(roleSessionName)
                    // lowest possible value for the credentials is 900 seconds
                    .durationSeconds(900)
                    .build();

            AssumeRoleWithWebIdentityResponse roleResponse = stsClient.assumeRoleWithWebIdentity(roleRequest);
            Credentials credentials = roleResponse.credentials();
            logger.info("New credentials, access key: {}, expiration: {}",
                    credentials.accessKeyId(),
                    credentials.expiration());
            return credentials;
        }
    }
}
