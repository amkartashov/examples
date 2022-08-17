package vertxtest;

import io.vertx.core.buffer.Buffer;
import io.vertx.core.net.JksOptions;
import io.vertx.core.net.PemTrustOptions;
import io.vertx.mysqlclient.MySQLAuthenticationPlugin;
import io.vertx.mysqlclient.MySQLConnectOptions;
import io.vertx.mysqlclient.MySQLPool;
import io.vertx.sqlclient.PoolOptions;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.RowSet;
import io.vertx.sqlclient.SqlClient;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Properties;

public class App {
    public static void main(String[] args) {
        String dbHost = System.getenv("DB_HOST");
        String dbName = System.getenv("DB_NAME");
        String dbUser = System.getenv("DB_USER");
        String dbPass = System.getenv("DB_PASS");

        // https://s3.amazonaws.com/rds-downloads/rds-ca-2019-root.pem
        String RDS_CA = "-----BEGIN CERTIFICATE-----\n" +
                "MIIEBjCCAu6gAwIBAgIJAMc0ZzaSUK51MA0GCSqGSIb3DQEBCwUAMIGPMQswCQYD\n" +
                "VQQGEwJVUzEQMA4GA1UEBwwHU2VhdHRsZTETMBEGA1UECAwKV2FzaGluZ3RvbjEi\n" +
                "MCAGA1UECgwZQW1hem9uIFdlYiBTZXJ2aWNlcywgSW5jLjETMBEGA1UECwwKQW1h\n" +
                "em9uIFJEUzEgMB4GA1UEAwwXQW1hem9uIFJEUyBSb290IDIwMTkgQ0EwHhcNMTkw\n" +
                "ODIyMTcwODUwWhcNMjQwODIyMTcwODUwWjCBjzELMAkGA1UEBhMCVVMxEDAOBgNV\n" +
                "BAcMB1NlYXR0bGUxEzARBgNVBAgMCldhc2hpbmd0b24xIjAgBgNVBAoMGUFtYXpv\n" +
                "biBXZWIgU2VydmljZXMsIEluYy4xEzARBgNVBAsMCkFtYXpvbiBSRFMxIDAeBgNV\n" +
                "BAMMF0FtYXpvbiBSRFMgUm9vdCAyMDE5IENBMIIBIjANBgkqhkiG9w0BAQEFAAOC\n" +
                "AQ8AMIIBCgKCAQEArXnF/E6/Qh+ku3hQTSKPMhQQlCpoWvnIthzX6MK3p5a0eXKZ\n" +
                "oWIjYcNNG6UwJjp4fUXl6glp53Jobn+tWNX88dNH2n8DVbppSwScVE2LpuL+94vY\n" +
                "0EYE/XxN7svKea8YvlrqkUBKyxLxTjh+U/KrGOaHxz9v0l6ZNlDbuaZw3qIWdD/I\n" +
                "6aNbGeRUVtpM6P+bWIoxVl/caQylQS6CEYUk+CpVyJSkopwJlzXT07tMoDL5WgX9\n" +
                "O08KVgDNz9qP/IGtAcRduRcNioH3E9v981QO1zt/Gpb2f8NqAjUUCUZzOnij6mx9\n" +
                "McZ+9cWX88CRzR0vQODWuZscgI08NvM69Fn2SQIDAQABo2MwYTAOBgNVHQ8BAf8E\n" +
                "BAMCAQYwDwYDVR0TAQH/BAUwAwEB/zAdBgNVHQ4EFgQUc19g2LzLA5j0Kxc0LjZa\n" +
                "pmD/vB8wHwYDVR0jBBgwFoAUc19g2LzLA5j0Kxc0LjZapmD/vB8wDQYJKoZIhvcN\n" +
                "AQELBQADggEBAHAG7WTmyjzPRIM85rVj+fWHsLIvqpw6DObIjMWokpliCeMINZFV\n" +
                "ynfgBKsf1ExwbvJNzYFXW6dihnguDG9VMPpi2up/ctQTN8tm9nDKOy08uNZoofMc\n" +
                "NUZxKCEkVKZv+IL4oHoeayt8egtv3ujJM6V14AstMQ6SwvwvA93EP/Ug2e4WAXHu\n" +
                "cbI1NAbUgVDqp+DRdfvZkgYKryjTWd/0+1fS8X1bBZVWzl7eirNVnHbSH2ZDpNuY\n" +
                "0SBd8dj5F6ld3t58ydZbrTHze7JJOd8ijySAp4/kiu9UfZWuTPABzDa/DSdz9Dk/\n" +
                "zPW4CXXvhLmE02TA9/HeCw3KEHIwicNuEfw=\n" +
                "-----END CERTIFICATE-----";

        try {
            // based on https://docs.aws.amazon.com/AmazonRDS/latest/AuroraUserGuide/UsingWithRDS.IAMDBAuth.Connecting.Java.html#UsingWithRDS.IAMDBAuth.Connecting.Java.AuthToken.Connect
            CertificateFactory certFactory = CertificateFactory.getInstance("X.509");
            X509Certificate rdsCaCert = (X509Certificate) certFactory.generateCertificate(new ByteArrayInputStream(RDS_CA.getBytes()));
            File keyStoreFile = File.createTempFile("rds-ca", ".jks");
            FileOutputStream fos = new FileOutputStream(keyStoreFile.getPath());
            KeyStore ks = KeyStore.getInstance("JKS", "SUN");
            ks.load(null);
            ks.setCertificateEntry("rootCaCertificate", rdsCaCert);
            ks.store(fos, "changeit".toCharArray());
            System.setProperty("javax.net.ssl.trustStore", keyStoreFile.getPath());
            System.setProperty("javax.net.ssl.trustStoreType", "JKS");
            System.setProperty("javax.net.ssl.trustStorePassword", "changeit");

            System.out.println(String.format("URI: mysql://%s@%s/%s", dbUser, dbHost, dbName));

            System.out.println("============ Try with MySQL connector ============");

            try {
                Properties mysqlConnectionProperties = new Properties();
                mysqlConnectionProperties.setProperty("verifyServerCertificate", "true");
                mysqlConnectionProperties.setProperty("useSSL", "true");
                mysqlConnectionProperties.setProperty("user", dbUser);
                mysqlConnectionProperties.setProperty("authenticationPlugins", "com.mysql.cj.protocol.a.authentication.MysqlClearPasswordPlugin");
                mysqlConnectionProperties.setProperty("password", dbPass);

                Connection con = DriverManager.getConnection(
                        String.format("jdbc:mysql://%s:3306/%s", dbHost, dbName), mysqlConnectionProperties);
                Statement stmt = con.createStatement();
                ResultSet rs = stmt.executeQuery("SELECT 1");
                while (rs.next())
                    System.out.println(rs.getInt(1));
                con.close();
            } catch (Exception e) {
                System.out.println(e);
            }

            System.out.println("============ Try with Vertx ============");
            SqlClient client = MySQLPool.client(
                    new MySQLConnectOptions()
                            .setAuthenticationPlugin(MySQLAuthenticationPlugin.MYSQL_CLEAR_PASSWORD)
                            .setSsl(true)
                            .setPort(3306)
                            .setHost(dbHost)
                            .setDatabase(dbName)
                            .setUser(dbUser)
                            .setPassword(dbPass)
                            .setTrustStoreOptions(new JksOptions()
                                    .setPath(keyStoreFile.getPath())
                                    .setPassword("changeit")),
                    new PoolOptions()
                            .setMaxSize(5)
            );

            client.query("SELECT 1").execute(ar -> {
                if (ar.succeeded()) {
                    RowSet<Row> result = ar.result();
                    System.out.println("Got " + result.size() + " rows ");
                } else {
                    System.out.println(ar.cause().getMessage());
                }
                client.close();
            });

        } catch (Exception ignored) {
        }
    }
}
